package com.paytm.booktickets.exceptions;

import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApiException(ApiException exception) {
		return ResponseEntity.status(exception.status()).body(new ApiError(
				exception.code(), exception.getMessage(), exception.seats(), MDC.get("request_id")));
	}

	@ExceptionHandler({org.springframework.dao.DataAccessException.class,
			org.springframework.transaction.TransactionSystemException.class})
	ResponseEntity<ApiError> handleDatabaseUnavailable(Exception ignored) {
		return ResponseEntity.status(503).body(new ApiError(
				"database_unavailable", "The booking store is temporarily unavailable.",
				List.of(), MDC.get("request_id")));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> handleMalformedJson() {
		return ResponseEntity.badRequest().body(new ApiError(
				"invalid_json", "Request body must be valid JSON.", List.of(), MDC.get("request_id")));
	}
}
