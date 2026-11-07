package com.kowalchuk.quoteservice.extraction.azure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.kowalchuk.quoteservice.extraction.ExtractionFailedException;

/**
 * Gets tokens from an Azure credential and reuses each one until shortly before it expires, so
 * an extraction doesn't pay for a token request every time.
 */
public class AzureCredentialTokenProvider implements AccessTokenProvider {

	/** The Entra ID scope for Azure AI services (Azure OpenAI included). */
	public static final String COGNITIVE_SERVICES_SCOPE = "https://cognitiveservices.azure.com/.default";

	// Refresh this long before expiry, so a token never expires mid-request.
	static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

	private final TokenCredential credential;

	private final Clock clock;

	private @Nullable AccessToken cached;

	public AzureCredentialTokenProvider(TokenCredential credential, Clock clock) {
		this.credential = credential;
		this.clock = clock;
	}

	@Override
	public synchronized String bearerToken() {
		Instant now = clock.instant();
		AccessToken current = cached;
		if (current == null || !current.getExpiresAt().toInstant().isAfter(now.plus(REFRESH_MARGIN))) {
			try {
				current = credential.getTokenSync(new TokenRequestContext().addScopes(COGNITIVE_SERVICES_SCOPE));
			}
			catch (RuntimeException ex) {
				throw new ExtractionFailedException("Couldn't get an Entra ID token for Azure AI.", ex);
			}
			cached = current;
		}
		return current.getToken();
	}

}
