package com.kowalchuk.quoteservice.config;

import java.net.URI;
import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for AI document extraction ({@code quote.extraction.*}). Extraction is switched on
 * only when {@code endpoint} is set, so local runs and tests need no Azure resources.
 *
 * @param endpoint the Azure AI resource, e.g. {@code https://ai-quote-service-dk.openai.azure.com}
 * @param deployment the model deployment name
 * @param reasoningEffort sent to reasoning models; blank leaves the model's default
 * @param managedIdentityClientId client ID of the user-assigned managed identity to sign in with;
 * blank on a developer machine, where the Azure CLI login is used
 * @param timeout how long to wait for the model before giving up
 */
@ConfigurationProperties("quote.extraction")
public record ExtractionProperties(
		@Nullable URI endpoint,
		@DefaultValue("gpt-5-mini") String deployment,
		@DefaultValue("low") String reasoningEffort,
		@Nullable String managedIdentityClientId,
		@DefaultValue("60s") Duration timeout) {
}
