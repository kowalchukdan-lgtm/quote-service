package com.kowalchuk.quoteservice.extraction.azure;

/**
 * Supplies a Microsoft Entra ID bearer token for Azure AI. There is no API key: in Azure the app
 * signs in as its managed identity, and on a developer's machine as the person logged in with
 * {@code az login}. Key-based access is disabled on the AI resource.
 */
@FunctionalInterface
public interface AccessTokenProvider {

	String bearerToken();

}
