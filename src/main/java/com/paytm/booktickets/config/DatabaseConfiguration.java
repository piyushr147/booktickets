package com.paytm.booktickets.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

@Configuration(proxyBeanMethods = false)
public class DatabaseConfiguration {

	@Bean
	@Primary
	HikariDataSource dataSource(Environment environment, ObjectProvider<JdbcConnectionDetails> connectionDetails) {
		return createDataSource(environment, connectionDetails.getIfAvailable(),
				environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 25),
				environment.getProperty("spring.datasource.hikari.minimum-idle", Integer.class, 2),
				environment.getProperty("spring.datasource.hikari.connection-timeout", Long.class, 30_000L),
				environment.getProperty("spring.datasource.hikari.validation-timeout", Long.class, 2_000L),
				1_000L, "booktickets-postgres");
	}

	@Bean("readinessDataSource")
	HikariDataSource readinessDataSource(Environment environment, ObjectProvider<JdbcConnectionDetails> connectionDetails) {
		return createDataSource(environment, connectionDetails.getIfAvailable(), 1, 0, 1_500L, 1_000L, -1L,
				"booktickets-readiness");
	}

	private HikariDataSource createDataSource(Environment environment, JdbcConnectionDetails serviceConnection,
			int maxPoolSize, int minIdle, long connectionTimeout, long validationTimeout,
			long initializationFailTimeout, String poolName) {
		String configuredUrl = environment.getProperty(
				"spring.datasource.url", "jdbc:postgresql://localhost:5432/booktickets");
		ParsedDatabaseUrl parsed = ParsedDatabaseUrl.parse(configuredUrl);

		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(serviceConnection == null ? parsed.jdbcUrl() : serviceConnection.getJdbcUrl());
		config.setUsername(firstNonBlank(
				serviceConnection == null ? environment.getProperty("spring.datasource.username") : serviceConnection.getUsername(),
				parsed.username()));
		config.setPassword(firstNonBlank(
				serviceConnection == null ? environment.getProperty("spring.datasource.password") : serviceConnection.getPassword(),
				parsed.password()));
		config.setMaximumPoolSize(maxPoolSize);
		config.setMinimumIdle(minIdle);
		config.setConnectionTimeout(connectionTimeout);
		config.setValidationTimeout(validationTimeout);
		config.setInitializationFailTimeout(initializationFailTimeout);
		config.setPoolName(poolName);
		return new HikariDataSource(config);
	}

	private static String firstNonBlank(String preferred, String fallback) {
		return preferred == null || preferred.isBlank() ? fallback : preferred;
	}

	private record ParsedDatabaseUrl(String jdbcUrl, String username, String password) {
		static ParsedDatabaseUrl parse(String value) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("DATABASE_URL must not be blank");
			}
			if (value.startsWith("jdbc:postgresql:")) {
				return new ParsedDatabaseUrl(value, null, null);
			}
			if (!value.startsWith("postgres://") && !value.startsWith("postgresql://")) {
				throw new IllegalArgumentException(
						"DATABASE_URL must use jdbc:postgresql://, postgres://, or postgresql://");
			}

			URI uri = URI.create(value);
			if (uri.getHost() == null || uri.getRawPath() == null || uri.getRawPath().length() < 2) {
				throw new IllegalArgumentException("DATABASE_URL must include a host and database name");
			}
			String authority = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
			String jdbcUrl = "jdbc:postgresql://" + authority + uri.getRawPath()
					+ (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
			String username = null;
			String password = null;
			if (uri.getRawUserInfo() != null) {
				String[] credentials = uri.getRawUserInfo().split(":", 2);
				username = decode(credentials[0]);
				password = credentials.length == 2 ? decode(credentials[1]) : "";
			}
			return new ParsedDatabaseUrl(jdbcUrl, username, password);
		}

		private static String decode(String value) {
			return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
		}
	}
}
