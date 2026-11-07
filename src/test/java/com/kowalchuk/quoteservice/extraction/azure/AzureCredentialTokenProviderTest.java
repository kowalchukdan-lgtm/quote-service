package com.kowalchuk.quoteservice.extraction.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.kowalchuk.quoteservice.extraction.ExtractionFailedException;

import reactor.core.publisher.Mono;

class AzureCredentialTokenProviderTest {

	private static final Instant START = Instant.parse("2026-11-07T12:00:00Z");

	private final MovableClock clock = new MovableClock(START);

	private final List<List<String>> requestedScopes = new ArrayList<>();

	private final TokenCredential credential = request -> {
		requestedScopes.add(request.getScopes());
		int number = requestedScopes.size();
		// Each token is valid for one hour from when it was issued.
		return Mono.just(new AccessToken("token-" + number, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
			.plusHours(1)));
	};

	private final AzureCredentialTokenProvider provider = new AzureCredentialTokenProvider(credential, clock);

	@Test
	void asksForTheAzureAiScope() {
		provider.bearerToken();

		assertThat(requestedScopes).containsExactly(List.of(AzureCredentialTokenProvider.COGNITIVE_SERVICES_SCOPE));
	}

	@Test
	void reusesATokenUntilShortlyBeforeItExpires() {
		assertThat(provider.bearerToken()).isEqualTo("token-1");

		clock.advance(Duration.ofMinutes(54));
		assertThat(provider.bearerToken()).isEqualTo("token-1");

		clock.advance(Duration.ofMinutes(1)); // now inside the five-minute refresh margin
		assertThat(provider.bearerToken()).isEqualTo("token-2");
		assertThat(requestedScopes).hasSize(2);
	}

	@Test
	void reportsACredentialFailureAsAnExtractionFailure() {
		TokenCredential broken = request -> Mono.error(new IllegalStateException("No az login"));
		AzureCredentialTokenProvider failing = new AzureCredentialTokenProvider(broken, clock);

		assertThatThrownBy(failing::bearerToken).isInstanceOf(ExtractionFailedException.class)
			.hasMessageContaining("Entra ID");
	}

	private static final class MovableClock extends Clock {

		private Instant now;

		MovableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
