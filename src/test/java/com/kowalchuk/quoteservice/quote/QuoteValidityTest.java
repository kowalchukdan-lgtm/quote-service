package com.kowalchuk.quoteservice.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.kowalchuk.quoteservice.rating.RatingResult.Declined;
import com.kowalchuk.quoteservice.rating.RatingResult.Reason;

/**
 * The validity boundary, one microsecond either side of {@code expiresAt}. At exactly
 * {@code expiresAt} the quote has expired.
 */
class QuoteValidityTest {

	private static final Instant EXPIRES_AT = Instant.parse("2026-11-22T14:05:13Z");

	private static final Instant CREATED_AT = EXPIRES_AT.minus(Duration.ofDays(30));

	@ParameterizedTest(name = "{0} µs from expiresAt: bindable={1}, status={2}")
	@CsvSource({ "-1, true, QUOTED", "0, false, EXPIRED", "1, false, EXPIRED" })
	void quotedQuoteExpiresAtExactlyExpiresAt(long micros, boolean bindable, QuoteStatus status) {
		Instant now = EXPIRES_AT.plusNanos(micros * 1_000);
		Quote quote = QuoteFixtures.quotedQuote(CREATED_AT, EXPIRES_AT);

		assertThat(QuoteValidity.isBindable(QuoteStatus.QUOTED, EXPIRES_AT, now)).isEqualTo(bindable);
		assertThat(quote.statusAt(now)).isEqualTo(status);
	}

	@ParameterizedTest(name = "{0} µs from expiresAt")
	@CsvSource({ "-1", "0", "1" })
	void referredQuoteIsNeverBindableAndNeverExpires(long micros) {
		Instant now = EXPIRES_AT.plusNanos(micros * 1_000);
		Quote quote = QuoteFixtures.unpricedQuote(QuoteFixtures.referred(), CREATED_AT);

		assertThat(QuoteValidity.isBindable(QuoteStatus.REFERRED, null, now)).isFalse();
		assertThat(quote.statusAt(now)).isEqualTo(QuoteStatus.REFERRED);
	}

	@ParameterizedTest(name = "{0} µs from expiresAt")
	@CsvSource({ "-1", "0", "1" })
	void declinedQuoteIsNeverBindableAndNeverExpires(long micros) {
		Instant now = EXPIRES_AT.plusNanos(micros * 1_000);
		Quote quote = QuoteFixtures.unpricedQuote(new Declined(List.of(new Reason("CLASS_NOT_WRITTEN", "No."))),
				CREATED_AT);

		assertThat(QuoteValidity.isBindable(QuoteStatus.DECLINED, null, now)).isFalse();
		assertThat(quote.statusAt(now)).isEqualTo(QuoteStatus.DECLINED);
	}

	@Test
	void quotedWithoutAnExpiryIsNotBindable() {
		// Impossible per the database CHECK, but defended.
		assertThat(QuoteValidity.isBindable(QuoteStatus.QUOTED, null, CREATED_AT)).isFalse();
	}

}
