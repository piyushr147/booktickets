#!/usr/bin/env python3
"""Exercise the seat service with a hot-seat race and retry-heavy sale burst."""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import random
import sys
import time
import uuid
from collections import Counter
from dataclasses import dataclass
from typing import Any

import httpx

# Demo-only defaults for the Render hiring-test deployment. Environment variables override these.
DEFAULT_BASE_URL = "https://booktickets-uee9.onrender.com"
DEFAULT_USER_TOKEN_MINT_SECRET = "ItbOqFPpk6RwBLs0T1cflDo8K9D3sjXz0DVB05kWQx8"
DEFAULT_ADMIN_TOKEN_MINT_SECRET = "M64S0SRpseMp5a-zOZfReH_bHSrOzG9FOxH929sN99k"

@dataclass(frozen=True)
class Result:
    status: int
    reason: str
    replay: bool = False
    error: str = ""


class Burst:
    def __init__(self, base_url: str, concurrency: int, timeout: float, verbose_http: bool = False):
        self.base_url = base_url.rstrip("/")
        limits = httpx.Limits(max_connections=concurrency, max_keepalive_connections=concurrency)
        self.client = httpx.AsyncClient(timeout=timeout, limits=limits)
        self.run_id = uuid.uuid4().hex[:12]
        self.verbose_http = verbose_http
        self.logger = logging.getLogger("burst")
        self.http_counts: Counter[str] = Counter()
        self.latencies_ms: list[float] = []
        self.started_at = time.perf_counter()

    async def close(self) -> None:
        await self.client.aclose()

    @staticmethod
    def safe_payload(payload: Any) -> Any:
        """Redact credentials before logging request or response bodies."""
        if isinstance(payload, dict):
            safe: dict[str, Any] = {}
            for key, value in payload.items():
                normalized_key = str(key).lower()
                if normalized_key in {"access_token", "refresh_token", "token", "authorization"}:
                    safe[key] = "[REDACTED]"
                elif normalized_key == "tokens" and isinstance(value, dict):
                    safe[key] = {"count": len(value), "values": "[REDACTED]"}
                else:
                    safe[key] = Burst.safe_payload(value)
            return safe
        if isinstance(payload, (list, tuple)):
            return [Burst.safe_payload(value) for value in payload]
        return payload

    @staticmethod
    def payload_text(payload: Any, limit: int = 1200) -> str:
        rendered = json.dumps(Burst.safe_payload(payload), default=str, ensure_ascii=False)
        if len(rendered) > limit:
            return rendered[:limit] + f"... [truncated; {len(rendered)} chars total]"
        return rendered

    def record_http(
        self,
        method: str,
        path: str,
        status: int,
        elapsed_ms: float,
        request_body: Any,
        response_body: Any,
        response_headers: httpx.Headers | None = None,
        error: str | None = None,
    ) -> None:
        self.latencies_ms.append(elapsed_ms)
        if status == 0:
            self.http_counts["network_error"] += 1
        else:
            self.http_counts[f"{status // 100}xx"] += 1

        details: dict[str, Any] = {
            "method": method,
            "url": self.base_url + path,
            "status": status or "NETWORK_ERROR",
            "elapsed_ms": round(elapsed_ms, 2),
        }
        if request_body is not None:
            details["request"] = self.safe_payload(request_body)
        if response_body is not None:
            details["response"] = self.safe_payload(response_body)
        if error:
            details["exception"] = error
        if response_headers:
            safe_headers = {
                name: response_headers[name]
                for name in ("x-request-id", "idempotency-replayed")
                if name in response_headers
            }
            if safe_headers:
                details["response_headers"] = safe_headers

        if status == 0 or status >= 500:
            self.logger.error("HTTP request failed: %s", self.payload_text(details))
        elif self.verbose_http:
            self.logger.info("HTTP exchange: %s", self.payload_text(details))

    def http_summary(self) -> dict[str, Any]:
        latencies = sorted(self.latencies_ms)

        def percentile(fraction: float) -> float | None:
            if not latencies:
                return None
            index = round((len(latencies) - 1) * fraction)
            return round(latencies[index], 2)

        latency_summary: dict[str, Any] = {"samples": len(latencies)}
        if latencies:
            latency_summary.update({
                "min": round(latencies[0], 2),
                "average": round(sum(latencies) / len(latencies), 2),
                "p95": percentile(0.95),
                "p99": percentile(0.99),
                "max": round(latencies[-1], 2),
            })
        return {
            "counts": dict(self.http_counts),
            "latency_ms": latency_summary,
            "elapsed_seconds": round(time.perf_counter() - self.started_at, 2),
        }

    async def send(
        self,
        method: str,
        path: str,
        *,
        headers: dict[str, str] | None = None,
        body: Any = None,
    ) -> tuple[int, Any, httpx.Headers]:
        started = time.perf_counter()
        try:
            response = await self.client.request(
                method,
                self.base_url + path,
                headers=headers,
                json=body,
            )
            try:
                parsed = response.json()
            except ValueError:
                parsed = response.text
            elapsed_ms = (time.perf_counter() - started) * 1000
            self.record_http(method, path, response.status_code, elapsed_ms, body, parsed, response.headers)
            return response.status_code, parsed, response.headers
        except Exception as exc:
            elapsed_ms = (time.perf_counter() - started) * 1000
            message = f"{type(exc).__name__}: {exc}"
            self.record_http(method, path, 0, elapsed_ms, body, None, error=message)
            return 0, None, httpx.Headers({"x-error": message})

    async def request(self, method: str, path: str, *, token: str | None = None,
                      key: str | None = None, body: Any = None) -> tuple[int, Any, httpx.Headers]:
        headers: dict[str, str] = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if key:
            headers["Idempotency-Key"] = key
        return await self.send(method, path, headers=headers, body=body)

    async def mint_tokens(self, users: list[str], user_secret: str, admin_secret: str) -> tuple[str, dict[str, str]]:
        status, admin, _ = await self.client_request_with_secret(
            "/auth/token", admin_secret, {"user_id": f"burst-admin-{self.run_id}", "role": "ADMIN"})
        if status != 200 or not isinstance(admin, dict) or "access_token" not in admin:
            raise RuntimeError(f"Could not mint an admin token (HTTP {status}): {admin}")
        status, response, _ = await self.client_request_with_secret(
            "/auth/tokens", user_secret, {"user_ids": users})
        if status != 200 or not isinstance(response, dict) or "tokens" not in response:
            raise RuntimeError(f"Could not mint user tokens (HTTP {status}): {response}")
        return admin["access_token"], response["tokens"]

    async def client_request_with_secret(self, path: str, secret: str, body: Any) -> tuple[int, Any, httpx.Headers]:
        return await self.send("POST", path, headers={"X-Token-Mint-Secret": secret}, body=body)

    async def create_show(self, admin_token: str, name: str, seats: list[str], limit: int = 4) -> dict[str, Any]:
        status, response, _ = await self.request("POST", "/shows", token=admin_token, body={
            "name": name,
            "seats": seats,
            "price_paise": 25000,
            "per_user_limit": limit,
        })
        if status != 201 or not isinstance(response, dict):
            raise RuntimeError(f"Could not create show (HTTP {status}): {response}")
        return response

    async def reserve(self, show_id: str, token: str, key: str, seat: str, spoof: bool = False) -> Result:
        body: dict[str, Any] = {"seats": [seat]}
        if spoof:
            body["user_id"] = "spoofed-user"
        status, response, headers = await self.request(
            "POST", f"/shows/{show_id}/reserve", token=token, key=key, body=body)
        if status == 0:
            return Result(0, "network_error", error=headers.get("x-error", "network error"))
        if status >= 500:
            return Result(status, "http_5xx", error=str(response))
        replay = headers.get("idempotency-replayed", "").lower() == "true"
        if replay:
            return Result(status, "idempotent_replay", replay=True)
        if status == 201:
            return Result(status, "confirmed")
        if isinstance(response, dict):
            return Result(status, str(response.get("code", f"http_{status}")))
        return Result(status, f"http_{status}", error=str(response))

    async def show_state(self, show_id: str) -> dict[str, Any]:
        status, response, _ = await self.request("GET", f"/shows/{show_id}")
        if status != 200 or not isinstance(response, dict):
            raise RuntimeError(f"Could not read show {show_id} (HTTP {status}): {response}")
        total = response["total_seats"]
        available = response["available"]
        held = response["held"]
        confirmed = response["confirmed"]
        if available + held + confirmed != total:
            raise RuntimeError(f"Show {show_id} failed seat reconciliation: {response}")
        if len(response["seats"]) != total:
            raise RuntimeError(f"Show {show_id} seat list does not match total_seats")
        return response


def parse_metrics(payload: str) -> dict[str, Any]:
    metrics: dict[str, Any] = {"counters": Counter(), "gauges": {}}
    for raw in payload.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("reservations_confirmed_total "):
            metrics["counters"]["confirmed"] = float(line.rsplit(" ", 1)[1])
        elif line.startswith("reservations_declined_total"):
            if "{" in line:
                labels, value = line.split("} ", 1)
                reason = labels.split('reason="', 1)[1].split('"', 1)[0]
                metrics["counters"][reason] = float(value)
        elif line.startswith("seats_available{"):
            labels, value = line.split("} ", 1)
            show_id = labels.split('show_id="', 1)[1].split('"', 1)[0]
            metrics["gauges"][show_id] = float(value)
    return metrics


async def get_metrics(burst: Burst) -> dict[str, Any]:
    status, response, _ = await burst.request("GET", "/actuator/prometheus")
    if status != 200 or not isinstance(response, str):
        raise RuntimeError(f"Could not read Prometheus metrics (HTTP {status})")
    return parse_metrics(response)


async def send_many(calls: list[tuple[str, str, str, str]], burst: Burst) -> list[Result]:
    return await asyncio.gather(*(burst.reserve(show_id, token, key, seat) for show_id, token, key, seat in calls))


def count_results(results: list[Result]) -> Counter[str]:
    outcomes: Counter[str] = Counter()
    for result in results:
        outcomes[result.reason] += 1
    return outcomes


async def run(args: argparse.Namespace) -> int:
    logger = logging.getLogger("burst")
    user_secret = os.environ.get("USER_TOKEN_MINT_SECRET", DEFAULT_USER_TOKEN_MINT_SECRET)
    admin_secret = os.environ.get("ADMIN_TOKEN_MINT_SECRET", DEFAULT_ADMIN_TOKEN_MINT_SECRET)
    if not user_secret or not admin_secret:
        raise RuntimeError("Set USER_TOKEN_MINT_SECRET and ADMIN_TOKEN_MINT_SECRET for the target service.")
    if args.requests < 500:
        raise RuntimeError("--requests must be at least 500 so the hot-seat scenario has a distinct-user storm.")

    burst = Burst(args.base_url, args.concurrency, args.timeout, verbose_http=args.verbose_http)
    phase = "initialize"
    logger.info(
        "Starting burst run_id=%s base_url=%s sale_requests=%d concurrency=%d timeout_seconds=%s",
        burst.run_id, burst.base_url, args.requests, args.concurrency, args.timeout,
    )
    try:
        phase = "mint test tokens"
        user_ids = [f"burst-{burst.run_id}-user-{index}" for index in range(500)]
        user_ids += [f"burst-{burst.run_id}-{name}" for name in ("quota", "owner", "attacker", "idempotency")]
        logger.info("PHASE 1/8: minting one ADMIN token and %d USER tokens", len(user_ids))
        admin_token, tokens = await burst.mint_tokens(user_ids, user_secret, admin_secret)

        phase = "read baseline Prometheus metrics"
        logger.info("PHASE 2/8: reading baseline Prometheus counters and availability gauges")
        baseline = await get_metrics(burst)
        counters = Counter()
        show_ids: list[str] = []

        phase = "sale burst"
        sale_seats = [f"S{index:03d}" for index in range(150)]
        sale_show = await burst.create_show(admin_token, f"sale-{burst.run_id}", sale_seats)
        show_ids.append(sale_show["id"])
        request_total = args.requests
        retry_count = round(request_total * 0.10)
        unique_count = request_total - retry_count
        logger.info(
            "PHASE 3/8: sale burst show_id=%s total=%d unique=%d retries=%d",
            sale_show["id"], request_total, unique_count, retry_count,
        )
        hot_labels = sale_seats[:10]
        spread_labels = sale_seats[10:]
        rng = random.Random(args.seed)
        unique_calls: list[tuple[str, str, str, str]] = []
        for index in range(unique_count):
            user_id = user_ids[index % 500]
            seat = rng.choice(hot_labels if rng.random() < 0.75 else spread_labels)
            unique_calls.append((sale_show["id"], tokens[user_id], f"sale-{burst.run_id}-{index}", seat))
        retries = [unique_calls[rng.randrange(len(unique_calls))] for _ in range(retry_count)]
        sale_results = await send_many(unique_calls + retries, burst)
        counters.update(count_results(sale_results))
        sale_state = await burst.show_state(sale_show["id"])
        logger.info("Sale burst outcomes=%s seat_state=%s", dict(count_results(sale_results)),
                    {key: sale_state[key] for key in ("total_seats", "available", "held", "confirmed")})

        phase = "hot-seat concurrency race"
        hot_show = await burst.create_show(admin_token, f"hot-seat-{burst.run_id}", ["HOT-1"])
        show_ids.append(hot_show["id"])
        logger.info("PHASE 4/8: sending 500 concurrent requests for one seat show_id=%s", hot_show["id"])
        hot_calls = [(hot_show["id"], tokens[user_ids[index]], f"hot-{burst.run_id}-{index}", "HOT-1")
                     for index in range(500)]
        hot_results = await send_many(hot_calls, burst)
        counters.update(count_results(hot_results))
        hot_confirmed = sum(result.reason == "confirmed" for result in hot_results)
        hot_taken = sum(result.reason == "seat_taken" for result in hot_results)
        if hot_confirmed != 1 or hot_taken != 499:
            raise RuntimeError(f"Hot-seat expectation failed: confirmed={hot_confirmed}, seat_taken={hot_taken}")
        hot_state = await burst.show_state(hot_show["id"])
        logger.info("Hot-seat check=PASS confirmed=%d seat_taken=%d", hot_confirmed, hot_taken)

        phase = "per-user reservation limit"
        quota_seats = [f"Q{index}" for index in range(10)]
        quota_show = await burst.create_show(admin_token, f"quota-{burst.run_id}", quota_seats, limit=4)
        show_ids.append(quota_show["id"])
        logger.info("PHASE 5/8: testing a four-seat per-user limit show_id=%s", quota_show["id"])
        quota_token = tokens[f"burst-{burst.run_id}-quota"]
        quota_calls = [(quota_show["id"], quota_token, f"quota-{burst.run_id}-{index}", seat)
                       for index, seat in enumerate(quota_seats)]
        quota_results = await send_many(quota_calls, burst)
        counters.update(count_results(quota_results))
        quota_confirmed = sum(result.reason == "confirmed" for result in quota_results)
        quota_limited = sum(result.reason == "per_user_limit" for result in quota_results)
        if quota_confirmed > 4 or quota_confirmed + quota_limited != 10:
            raise RuntimeError(f"Per-user limit expectation failed: confirmed={quota_confirmed}, limited={quota_limited}")
        quota_state = await burst.show_state(quota_show["id"])
        logger.info("Per-user limit check=PASS confirmed=%d limited=%d", quota_confirmed, quota_limited)

        phase = "idempotency conflict handling"
        idempotency_show = await burst.create_show(admin_token, f"idempotency-{burst.run_id}", ["I1", "I2"])
        show_ids.append(idempotency_show["id"])
        logger.info("PHASE 6/8: reusing an idempotency key with a different body show_id=%s",
                    idempotency_show["id"])
        idem_user = tokens[f"burst-{burst.run_id}-idempotency"]
        first = await burst.reserve(idempotency_show["id"], idem_user, f"conflict-{burst.run_id}", "I1")
        mismatch_status, mismatch_body, _ = await burst.request("POST", f"/shows/{idempotency_show['id']}/reserve",
            token=idem_user, key=f"conflict-{burst.run_id}", body={"seats": ["I2"]})
        if first.reason != "confirmed" or not isinstance(mismatch_body, dict) \
                or mismatch_status != 409 or mismatch_body.get("code") != "idempotency_conflict":
            raise RuntimeError(
                f"Same-key/different-body expectation failed: first={first}, "
                f"mismatch_status={mismatch_status}, mismatch_body={mismatch_body}"
            )
        counters.update({first.reason: 1, "idempotency_conflict": 1})
        idem_state = await burst.show_state(idempotency_show["id"])
        logger.info("Idempotency conflict check=PASS status=%d code=%s", mismatch_status,
                    mismatch_body.get("code"))

        phase = "identity and owner authorization"
        owner_id = f"burst-{burst.run_id}-owner"
        owner_token = tokens[owner_id]
        attacker_token = tokens[f"burst-{burst.run_id}-attacker"]
        cancel_show = await burst.create_show(admin_token, f"cancel-{burst.run_id}", ["C1"])
        show_ids.append(cancel_show["id"])
        logger.info("PHASE 7/8: checking JWT identity and non-owner cancellation show_id=%s",
                    cancel_show["id"])
        spoof_status, spoof_body, _ = await burst.request(
            "POST", f"/shows/{cancel_show['id']}/reserve", token=owner_token,
            key=f"spoof-{burst.run_id}", body={"seats": ["C1"], "user_id": "spoofed-user"})
        if spoof_status != 201 or not isinstance(spoof_body, dict) or spoof_body.get("user_id") != owner_id:
            raise RuntimeError(f"Token identity was not used for the reservation: {spoof_body}")
        reservation_id = spoof_body["reservation_id"]
        cancel_status, _, _ = await burst.request(
            "POST", f"/reservations/{reservation_id}/cancel", token=attacker_token, body={})
        if cancel_status != 404:
            raise RuntimeError(f"Non-owner cancellation should return 404, got {cancel_status}")
        counters.update({"confirmed": 1, "reservation_not_found": 1})
        cancel_state = await burst.show_state(cancel_show["id"])
        logger.info("Identity and owner checks=PASS reservation_owner=%s non_owner_cancel_status=%d",
                    owner_id, cancel_status)

        phase = "verify Prometheus metrics and final results"
        logger.info("PHASE 8/8: waiting for metric updates and reading final Prometheus values")
        await asyncio.sleep(1.2)
        final_metrics = await get_metrics(burst)
        metric_deltas = Counter()
        for name, final_value in final_metrics["counters"].items():
            metric_deltas[name] = final_value - baseline["counters"].get(name, 0.0)
        metric_checks = {
            "confirmed": sum(result.reason == "confirmed" for result in sale_results)
                          + hot_confirmed + quota_confirmed + 2,
            "seat_taken": counters["seat_taken"],
            "per_user_limit": counters["per_user_limit"],
            "idempotency_conflict": 1,
            "idempotent_replay": counters["idempotent_replay"],
        }
        mismatches = {}
        for reason, expected in metric_checks.items():
            actual = metric_deltas.get(reason, 0.0)
            if actual != expected:
                mismatches[reason] = {"expected": expected, "actual": actual}
        gauge_checks = {}
        for show_id, state in ((sale_show["id"], sale_state), (hot_show["id"], hot_state),
                               (quota_show["id"], quota_state), (idempotency_show["id"], idem_state),
                               (cancel_show["id"], cancel_state)):
            gauge_checks[show_id] = {"api": state["available"], "prometheus": final_metrics["gauges"].get(show_id)}
            if final_metrics["gauges"].get(show_id) != state["available"]:
                mismatches[f"seats_available:{show_id}"] = gauge_checks[show_id]

        transport_errors = burst.http_counts["5xx"] + burst.http_counts["network_error"]
        checks = {
            "sale_requests_completed": len(sale_results) == request_total,
            "hot_seat_single_winner": hot_confirmed == 1 and hot_taken == 499,
            "per_user_limit_enforced": quota_confirmed <= 4 and quota_confirmed + quota_limited == 10,
            "idempotency_conflict_rejected": mismatch_status == 409,
            "jwt_identity_and_owner_check": spoof_status == 201 and cancel_status == 404,
            "prometheus_matches_api_state": not mismatches,
            "no_http_5xx_or_network_errors": transport_errors == 0,
        }
        passed = all(checks.values())
        output = {
            "result": "PASS" if passed else "FAIL",
            "base_url": burst.base_url,
            "run_id": burst.run_id,
            "checks": checks,
            "requests": {
                "sale": request_total,
                "sale_unique": unique_count,
                "sale_retries": retry_count,
                "hot_seat": 500,
                "per_user_limit": 10,
            },
            "outcomes": {
                "sale": dict(count_results(sale_results)),
                "hot_seat": dict(count_results(hot_results)),
                "per_user_limit": dict(count_results(quota_results)),
                "idempotency_conflict": "passed",
                "spoof_and_cancel": "passed",
            },
            "api_reconciliation": {
                "sale": {key: sale_state[key] for key in ("total_seats", "available", "held", "confirmed")},
                "hot_seat": {key: hot_state[key] for key in ("total_seats", "available", "held", "confirmed")},
                "per_user_limit": {key: quota_state[key] for key in ("total_seats", "available", "held", "confirmed")},
                "idempotency": {key: idem_state[key] for key in ("total_seats", "available", "held", "confirmed")},
                "cancel": {key: cancel_state[key] for key in ("total_seats", "available", "held", "confirmed")},
            },
            "prometheus_counter_deltas": dict(metric_deltas),
            "available_gauges": gauge_checks,
            "metric_mismatches": mismatches,
            "five_xx_or_network_errors": counters["http_5xx"] + counters["network_error"],
            "http": burst.http_summary(),
        }
        logger.info("BURST_RESULT %s", json.dumps(output, sort_keys=True))
        print(json.dumps(output, indent=2, sort_keys=True))
        return 0 if passed else 1
    except Exception as exc:
        logger.exception("Burst test failed during phase=%s run_id=%s", phase, burst.run_id)
        failure = {
            "result": "FAIL",
            "base_url": burst.base_url,
            "run_id": burst.run_id,
            "failed_phase": phase,
            "error": f"{type(exc).__name__}: {exc}",
            "http": burst.http_summary(),
        }
        logger.error("BURST_RESULT %s", json.dumps(failure, sort_keys=True))
        print(json.dumps(failure, indent=2, sort_keys=True))
        return 1
    finally:
        await burst.close()


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "base_url",
        nargs="?",
        default=DEFAULT_BASE_URL,
        help=f"Service base URL (default: {DEFAULT_BASE_URL})",
    )
    parser.add_argument("--requests", type=int, default=20_000, help="On-sale request count (default: 20000)")
    parser.add_argument("--concurrency", type=int, default=500, help="Maximum in-flight HTTP connections")
    parser.add_argument("--timeout", type=float, default=120.0, help="HTTP timeout in seconds")
    parser.add_argument("--seed", type=int, default=2212, help="Deterministic seat distribution seed")
    parser.add_argument(
        "--verbose-http",
        action="store_true",
        help="Log every HTTP request and response (can produce many lines for large bursts)",
    )
    parser.add_argument("--log-file", help="Also save progress and error logs to this file")
    args = parser.parse_args()
    if args.concurrency < 1 or args.timeout <= 0:
        parser.error("--concurrency and --timeout must be positive")
    return args


def configure_logging(log_file: str | None) -> None:
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stderr)]
    if log_file:
        handlers.append(logging.FileHandler(log_file, encoding="utf-8"))
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S",
        handlers=handlers,
        force=True,
    )


if __name__ == "__main__":
    parsed_args = arguments()
    configure_logging(parsed_args.log_file)
    logger = logging.getLogger("burst")
    try:
        raise SystemExit(asyncio.run(run(parsed_args)))
    except Exception as exc:
        logger.exception("Burst runner failed before or outside its test phases")
        print(json.dumps({"result": "FAIL", "error": f"{type(exc).__name__}: {exc}"}, indent=2))
        raise SystemExit(1)
