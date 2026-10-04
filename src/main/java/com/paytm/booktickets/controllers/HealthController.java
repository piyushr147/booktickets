package com.paytm.booktickets.controllers;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
	private final HikariDataSource dataSource;

	public HealthController(@Qualifier("readinessDataSource") HikariDataSource dataSource) {
		this.dataSource = dataSource;
	}

	@GetMapping("/health/live")
	public Map<String, String> live() {
		return Map.of("status", "UP");
	}

	@GetMapping("/health/ready")
	public ResponseEntity<Map<String, String>> ready() {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.setQueryTimeout(2);
			try (ResultSet result = statement.executeQuery("SELECT 1")) {
				if (result.next() && result.getInt(1) == 1) {
					return ResponseEntity.ok(Map.of("status", "UP", "dependency", "postgres"));
				}
			}
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(Map.of("status", "DOWN", "dependency", "postgres"));
		} catch (Exception exception) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(Map.of("status", "DOWN", "dependency", "postgres"));
		}
	}
}
