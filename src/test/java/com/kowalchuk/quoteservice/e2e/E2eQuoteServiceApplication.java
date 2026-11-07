package com.kowalchuk.quoteservice.e2e;

import org.springframework.boot.SpringApplication;

import com.kowalchuk.quoteservice.QuoteServiceApplication;
import com.kowalchuk.quoteservice.TestcontainersConfiguration;

/**
 * The real application for the Playwright tests: a fresh Postgres in Docker, and the golden-set
 * fake in place of the AI model. {@code frontend/playwright.config.ts} starts it with:
 *
 * <pre>
 * ./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.kowalchuk.quoteservice.e2e.E2eQuoteServiceApplication \
 *     -Dspring-boot.run.profiles=e2e -Dspring-boot.run.arguments=--server.port=8081
 * </pre>
 */
public class E2eQuoteServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(QuoteServiceApplication::main)
			.with(TestcontainersConfiguration.class, E2eModelConfiguration.class)
			.run(args);
	}

}
