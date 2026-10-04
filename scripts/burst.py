#!/usr/bin/env python3
"""Exercise the seat service with a hot-seat race and retry-heavy sale burst."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import random
import sys
import uuid
from collections import Counter
from dataclasses import dataclass
from typing import Any

import httpx


@dataclass(frozen=True)
class Result:
    status: int
    reason: str
    replay: bool = False
    error: str = ""


class Burst:
    def __init__(self, base_url: str, concurrency: int, timeout: float):
        self.base_url = base_url.rstrip("/")
        limits = httpx.Limits(max_connections=concurrency, max_keepalive_connections=concurrency)
        self.client = httpx.AsyncClient(timeout=timeout, limits=limits)
        self.run_id = uuid.uuid4().hex[:12]

    async def close(self) -> None:
        await self.client.aclose()

    async def request(self, method: str, path: str, *, token: str | None = None,
                      key: str | None = None, body: Any = None) -> tuple[int, Any, httpx.Headers]:
        headers: dict[str, str] = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if key:
            headers["Idempotency-Key"] = key
        try:
            response = await self.client.request(method, self.base_url + path, headers=headers, json=body)
            try:
                parsed = response.json()
            except ValueError:
                parsed = response.text
            return response.status_code, parsed, response.headers
        except Exception as exc:  # Network errors are counted separately from HTTP 5xx.
            return 0, None, httpx.Headers({"x-error": f"{type(exc).__name__}: {exc}"})

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
        try:
            response = await self.client.post(
                self.base_url + path,
                headers={"X-Token-Mint-Secret": secret},
                json=body,
            )
            try:
                parsed = response.json()
            except ValueError:
                parsed = response.text
            return response.status_code, parsed, response.headers
        except Exception as exc:
            return 0, None, httpx.Headers({"x-error": f"{type(exc).__name__}: {exc}"})

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


async def send_many(calls: list[tuple[str, str, str]], burst: Burst) -> list[Result]:
    return await asyncio.gather(*(burst.reserve(show_id, token, key, seat) for show_id, token, key, seat in calls))


def count_results(results: list[Result]) -> Counter[str]:
    outcomes: Counter[str] = Counter()
    for result in results:
        outcomes[result.reason] += 1
    return outcomes


async def run(args: argparse.Namespace) -> int:
    user_secret = os.environ.get("USER_TOKEN_MINT_SECRET", "")
    admin_secret = os.environ.get("ADMIN_TOKEN_MINT_SECRET", "")
    if not user_secret or not admin_secret:
        raise RuntimeError("Set USER_TOKEN_MINT_SECRET and ADMIN_TOKEN_MINT_SECRET for the target service.")
    if args.requests < 500:
        raise RuntimeError("--requests must be at least 500 so the hot-seat scenario has a distinct-user storm.")

    burst = Burst(args.base_url, args.concurrency, args.timeout)
    try:
        user_ids = [f"burst-{burst.run_id}-user-{index}" for index in range(500)]
        user_ids += [f"burst-{burst.run_id}-{name}" for name in ("quota", "owner", "attacker", "idempotency")]
        admin_token, tokens = await burst.mint_tokens(user_ids, user_secret, admin_secret)
        baseline = await get_metrics(burst)
        counters = Counter()
        show_ids: list[str] = []

        sale_seats = [f"S{index:03d}" for index in range(150)]
        sale_show = await burst.create_show(admin_token, f"sale-{burst.run_id}", sale_seats)
        show_ids.append(sale_show["id"])
        request_total = args.requests
        retry_count = round(request_total * 0.10)
        unique_count = request_total - retry_count
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

        hot_show = await burst.create_show(admin_token, f"hot-seat-{burst.run_id}", ["HOT-1"])
        show_ids.append(hot_show["id"])
        hot_calls = [(hot_show["id"], tokens[user_ids[index]], f"hot-{burst.run_id}-{index}", "HOT-1")
                     for index in range(500)]
        hot_results = await send_many(hot_calls, burst)
        counters.update(count_results(hot_results))
        hot_confirmed = sum(result.reason == "confirmed" for result in hot_results)
        hot_taken = sum(result.reason == "seat_taken" for result in hot_results)
        if hot_confirmed != 1 or hot_taken != 499:
            raise RuntimeError(f"Hot-seat expectation failed: confirmed={hot_confirmed}, seat_taken={hot_taken}")
        hot_state = await burst.show_state(hot_show["id"])

        quota_seats = [f"Q{index}" for index in range(10)]
        quota_show = await burst.create_show(admin_token, f"quota-{burst.run_id}", quota_seats, limit=4)
        show_ids.append(quota_show["id"])
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

        idempotency_show = await burst.create_show(admin_token, f"idempotency-{burst.run_id}", ["I1", "I2"])
        show_ids.append(idempotency_show["id"])
        idem_user = tokens[f"burst-{burst.run_id}-idempotency"]
        first = await burst.reserve(idempotency_show["id"], idem_user, f"conflict-{burst.run_id}", "I1")
        _, mismatch_body, _ = await burst.request("POST", f"/shows/{idempotency_show['id']}/reserve",
            token=idem_user, key=f"conflict-{burst.run_id}", body={"seats": ["I2"]})
        if first.reason != "confirmed" or not isinstance(mismatch_body, dict) \
                or mismatch_body.get("code") != "idempotency_conflict":
            raise RuntimeError(f"Same-key/different-body expectation failed: first={first}, mismatch={mismatch_body}")
        counters.update({first.reason: 1, "idempotency_conflict": 1})
        idem_state = await burst.show_state(idempotency_show["id"])

        owner_id = f"burst-{burst.run_id}-owner"
        owner_token = tokens[owner_id]
        attacker_token = tokens[f"burst-{burst.run_id}-attacker"]
        cancel_show = await burst.create_show(admin_token, f"cancel-{burst.run_id}", ["C1"])
        show_ids.append(cancel_show["id"])
        spoof_status, spoof_body, _ = await burst.request(
            "POST", f"/shows/{cancel_show['id']}/reserve", token=owner_token,
            key=f"spoof-{burst.run_id}", body={"seats": ["C1"], "user_id": "spoofed-user"})
        if spoof_status != 201 or spoof_body.get("user_id") != owner_id:
            raise RuntimeError(f"Token identity was not used for the reservation: {spoof_body}")
        reservation_id = spoof_body["reservation_id"]
        cancel_status, _, _ = await burst.request(
            "POST", f"/reservations/{reservation_id}/cancel", token=attacker_token, body={})
        if cancel_status != 404:
            raise RuntimeError(f"Non-owner cancellation should return 404, got {cancel_status}")
        counters.update({"confirmed": 1, "reservation_not_found": 1})
        cancel_state = await burst.show_state(cancel_show["id"])

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

        output = {
            "base_url": burst.base_url,
            "run_id": burst.run_id,
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
        }
        print(json.dumps(output, indent=2, sort_keys=True))
        return 1 if mismatches or output["five_xx_or_network_errors"] else 0
    finally:
        await burst.close()


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url", help="Service base URL, for example http://localhost:8080")
    parser.add_argument("--requests", type=int, default=20_000, help="On-sale request count (default: 20000)")
    parser.add_argument("--concurrency", type=int, default=500, help="Maximum in-flight HTTP connections")
    parser.add_argument("--timeout", type=float, default=120.0, help="HTTP timeout in seconds")
    parser.add_argument("--seed", type=int, default=2212, help="Deterministic seat distribution seed")
    args = parser.parse_args()
    if args.concurrency < 1 or args.timeout <= 0:
        parser.error("--concurrency and --timeout must be positive")
    return args


if __name__ == "__main__":
    try:
        raise SystemExit(asyncio.run(run(arguments())))
    except Exception as exc:
        print(f"burst failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
