package com.kowalchuk.quoteservice.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the intake archive in Cosmos DB ({@code quote.archive.cosmos.*}). The archive is
 * on only when {@code endpoint} is set, so local runs and tests need no Azure resources.
 *
 * @param endpoint the Cosmos DB account, e.g. {@code https://cosmos-quote-service-dk.documents.azure.com:443/}
 * @param database created with the Azure CLI, never by the app
 * @param container created with the Azure CLI, partition key {@code /entityId}
 * @param managedIdentityClientId client ID of the user-assigned managed identity to sign in with
 * (the same one as extraction); blank on a developer machine, where the Azure CLI login is used
 */
@ConfigurationProperties("quote.archive.cosmos")
public record ArchiveProperties(
		@Nullable String endpoint,
		@DefaultValue("quotes") String database,
		@DefaultValue("intake-archive") String container,
		@Nullable String managedIdentityClientId) {
}
