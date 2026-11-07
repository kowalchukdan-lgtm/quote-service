package com.kowalchuk.quoteservice.config;

import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.kowalchuk.quoteservice.extraction.DocumentExtractor;
import com.kowalchuk.quoteservice.extraction.DocumentTextReader;
import com.kowalchuk.quoteservice.extraction.ExtractionPrompt;
import com.kowalchuk.quoteservice.extraction.ExtractionResponseParser;
import com.kowalchuk.quoteservice.extraction.ModelClient;
import com.kowalchuk.quoteservice.extraction.azure.AccessTokenProvider;
import com.kowalchuk.quoteservice.extraction.azure.AzureCredentialTokenProvider;
import com.kowalchuk.quoteservice.extraction.azure.AzureOpenAiModelClient;

/**
 * Wires AI document extraction when {@code quote.extraction.endpoint} is set. The extraction
 * classes stay free of Spring; this is the only place they meet it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExtractionProperties.class)
@ConditionalOnProperty(prefix = "quote.extraction", name = "endpoint")
public class ExtractionConfig {

	/**
	 * Managed identity in Azure, the Azure CLI login on a developer machine. With a
	 * user-assigned identity, its client ID says which identity to use.
	 */
	@Bean
	TokenCredential extractionCredential(ExtractionProperties properties) {
		DefaultAzureCredentialBuilder builder = new DefaultAzureCredentialBuilder();
		String clientId = properties.managedIdentityClientId();
		if (clientId != null && !clientId.isBlank()) {
			builder.managedIdentityClientId(clientId);
		}
		return builder.build();
	}

	@Bean
	AccessTokenProvider extractionTokens(TokenCredential extractionCredential, Clock clock) {
		return new AzureCredentialTokenProvider(extractionCredential, clock);
	}

	@Bean
	ModelClient extractionModelClient(AccessTokenProvider extractionTokens, ExtractionProperties properties) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
		requestFactory.setReadTimeout(properties.timeout());
		// Its own client rather than the shared builder: this call has its own timeouts, and the
		// API's strict request-parsing settings aren't meant for a third-party response.
		RestClient restClient = RestClient.builder()
			.baseUrl(String.valueOf(properties.endpoint()))
			.requestFactory(requestFactory)
			.build();
		return new AzureOpenAiModelClient(restClient, extractionTokens, properties.deployment(),
				properties.reasoningEffort());
	}

	@Bean
	DocumentExtractor documentExtractor(ModelClient extractionModelClient, Clock clock) {
		return new DocumentExtractor(new DocumentTextReader(), new ExtractionPrompt(), extractionModelClient,
				new ExtractionResponseParser(), clock);
	}

}
