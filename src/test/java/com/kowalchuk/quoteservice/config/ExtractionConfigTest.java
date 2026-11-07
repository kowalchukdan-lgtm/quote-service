package com.kowalchuk.quoteservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.kowalchuk.quoteservice.extraction.DocumentExtractor;
import com.kowalchuk.quoteservice.extraction.ModelClient;

class ExtractionConfigTest {

	private final ApplicationContextRunner contexts = new ApplicationContextRunner()
		.withUserConfiguration(ExtractionConfig.class)
		.withBean(Clock.class, Clock::systemUTC);

	@Test
	void extractionIsOffWithoutAnEndpoint() {
		contexts.run(context -> assertThat(context).doesNotHaveBean(DocumentExtractor.class)
			.doesNotHaveBean(ModelClient.class));
	}

	@Test
	void extractionIsWiredWhenAnEndpointIsSet() {
		contexts
			.withPropertyValues("quote.extraction.endpoint=https://ai-quote-service.test",
					"quote.extraction.managed-identity-client-id=00000000-0000-0000-0000-000000000000")
			.run(context -> {
				assertThat(context).hasSingleBean(DocumentExtractor.class).hasSingleBean(ModelClient.class);
				ExtractionProperties properties = context.getBean(ExtractionProperties.class);
				assertThat(properties.deployment()).isEqualTo("gpt-5-mini");
				assertThat(properties.reasoningEffort()).isEqualTo("low");
				assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(60));
			});
	}

	@Test
	void usesTheCliLoginWhenNoManagedIdentityIsNamed() {
		contexts.withPropertyValues("quote.extraction.endpoint=https://ai-quote-service.test")
			.run(context -> assertThat(context).hasSingleBean(DocumentExtractor.class));
	}

}
