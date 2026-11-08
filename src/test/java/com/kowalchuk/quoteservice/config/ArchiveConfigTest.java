package com.kowalchuk.quoteservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.kowalchuk.quoteservice.archive.ArchiveDispatcher;
import com.kowalchuk.quoteservice.archive.IntakeArchive;
import com.kowalchuk.quoteservice.archive.cosmos.CosmosIntakeArchive;

class ArchiveConfigTest {

	private final ApplicationContextRunner contexts = new ApplicationContextRunner()
		.withUserConfiguration(ArchiveConfig.class);

	@Test
	void keepsNothingWithoutAnEndpoint() {
		contexts.run(context -> assertThat(context).hasSingleBean(ArchiveDispatcher.class)
			.doesNotHaveBean(IntakeArchive.class)
			.doesNotHaveBean(ArchiveProperties.class));
	}

	@Test
	void archivesToCosmosWhenAnEndpointIsSet() {
		contexts
			.withPropertyValues("quote.archive.cosmos.endpoint=https://cosmos-quote-service.test:443/",
					"quote.archive.cosmos.managed-identity-client-id=00000000-0000-0000-0000-000000000000")
			.run(context -> {
				assertThat(context).hasSingleBean(ArchiveDispatcher.class).hasSingleBean(CosmosIntakeArchive.class);
				ArchiveProperties properties = context.getBean(ArchiveProperties.class);
				assertThat(properties.database()).isEqualTo("quotes");
				assertThat(properties.container()).isEqualTo("intake-archive");
			});
	}

	@Test
	void usesTheCliLoginWhenNoManagedIdentityIsNamed() {
		contexts.withPropertyValues("quote.archive.cosmos.endpoint=https://cosmos-quote-service.test:443/")
			.run(context -> assertThat(context).hasSingleBean(CosmosIntakeArchive.class));
	}

}
