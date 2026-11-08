package com.kowalchuk.quoteservice.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.kowalchuk.quoteservice.archive.ArchiveDispatcher;
import com.kowalchuk.quoteservice.archive.IntakeArchive;
import com.kowalchuk.quoteservice.archive.NoOpIntakeArchive;
import com.kowalchuk.quoteservice.archive.cosmos.CosmosIntakeArchive;

/**
 * Wires the intake archive: Cosmos DB when {@code quote.archive.cosmos.endpoint} is set, and
 * otherwise an archive that keeps nothing. Either way, events reach it through the dispatcher,
 * after commit and off the request thread.
 */
@Configuration(proxyBeanMethods = false)
public class ArchiveConfig {

	static final int THREADS = 2;

	static final int QUEUE_CAPACITY = 1000;

	static final int SHUTDOWN_WAIT_SECONDS = 10;

	/**
	 * The executor is created here rather than as a bean, which would replace Spring Boot's own
	 * application task executor.
	 */
	@Bean
	ArchiveDispatcher archiveDispatcher(ObjectProvider<IntakeArchive> archive) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("intake-archive-");
		executor.setCorePoolSize(THREADS);
		executor.setMaxPoolSize(THREADS);
		executor.setQueueCapacity(QUEUE_CAPACITY);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
		executor.initialize();
		return new ArchiveDispatcher(archive.getIfAvailable(NoOpIntakeArchive::new), executor);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ArchiveProperties.class)
	@ConditionalOnProperty(prefix = "quote.archive.cosmos", name = "endpoint")
	static class Cosmos {

		/**
		 * Managed identity in Azure, the Azure CLI login on a developer machine; never a key.
		 * The credential isn't a bean: a second one would make extraction's ambiguous.
		 */
		@Bean
		CosmosIntakeArchive cosmosIntakeArchive(ArchiveProperties properties) {
			DefaultAzureCredentialBuilder credential = new DefaultAzureCredentialBuilder();
			String clientId = properties.managedIdentityClientId();
			if (clientId != null && !clientId.isBlank()) {
				credential.managedIdentityClientId(clientId);
			}
			return CosmosIntakeArchive.connectingTo(String.valueOf(properties.endpoint()), credential.build(),
					properties.database(), properties.container());
		}

	}

}
