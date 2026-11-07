package com.kowalchuk.quoteservice.e2e;

import java.time.Clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import com.kowalchuk.quoteservice.extraction.DocumentExtractor;
import com.kowalchuk.quoteservice.extraction.DocumentTextReader;
import com.kowalchuk.quoteservice.extraction.ExtractionPrompt;
import com.kowalchuk.quoteservice.extraction.ExtractionResponseParser;

/**
 * Real extraction with the golden-set fake in place of the model, for the Playwright tests. It
 * lives in test code, so it is never in the jar or the image, and it needs the {@code e2e}
 * profile as well. Being a {@code @TestConfiguration}, other tests' component scans skip it.
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("e2e")
public class E2eModelConfiguration {

	@Bean
	DocumentExtractor documentExtractor(Clock clock) {
		return new DocumentExtractor(new DocumentTextReader(), new ExtractionPrompt(), new GoldenSetModelClient(),
				new ExtractionResponseParser(), clock);
	}

}
