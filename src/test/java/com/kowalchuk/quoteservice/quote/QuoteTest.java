package com.kowalchuk.quoteservice.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * {@link Quote#create} rejects values Postgres would otherwise round silently, so the engine's
 * single rounding is the only one.
 */
class QuoteTest {

	private static final Instant CREATED_AT = Instant.parse("2026-10-23T14:05:13Z");

	private static final Instant EXPIRES_AT = CREATED_AT.plus(Duration.ofDays(30));

	@Test
	void premiumMustBeExactlyInCents() {
		assertThatIllegalArgumentException().isThrownBy(() -> Quote.create("qte_TEST", "sub_TEST",
				QuoteFixtures.INPUTS, QuoteFixtures.ROWS, QuoteFixtures.quoted("2520.005", "2.1000"), "USD",
				CREATED_AT, EXPIRES_AT))
			.withMessageContaining("premium");
		assertThatIllegalArgumentException().isThrownBy(() -> Quote.create("qte_TEST", "sub_TEST",
				QuoteFixtures.INPUTS, QuoteFixtures.ROWS, QuoteFixtures.quoted("2520.0", "2.1000"), "USD", CREATED_AT,
				EXPIRES_AT));
	}

	@Test
	void factorMayHaveAtMostFourDecimals() {
		assertThatIllegalArgumentException().isThrownBy(() -> Quote.create("qte_TEST", "sub_TEST",
				QuoteFixtures.INPUTS, QuoteFixtures.ROWS, QuoteFixtures.quoted("2520.00", "2.10001"), "USD",
				CREATED_AT, EXPIRES_AT))
			.withMessageContaining("CLASS");
		// Trailing zeros are not extra precision.
		assertThat(Quote.create("qte_TEST", "sub_TEST", QuoteFixtures.INPUTS, QuoteFixtures.ROWS,
				QuoteFixtures.quoted("2520.00", "2.100000"), "USD", CREATED_AT, EXPIRES_AT)
			.getRatingFactors()).hasSize(1);
	}

	@Test
	void expiryIsPresentExactlyWhenPriced() {
		assertThatIllegalArgumentException().isThrownBy(() -> Quote.create("qte_TEST", "sub_TEST",
				QuoteFixtures.INPUTS, QuoteFixtures.ROWS, QuoteFixtures.quoted("2520.00", "2.1000"), "USD",
				CREATED_AT, null));
		assertThatIllegalArgumentException().isThrownBy(() -> Quote.create("qte_TEST", "sub_TEST",
				QuoteFixtures.INPUTS, QuoteFixtures.ROWS, QuoteFixtures.referred(), "USD", CREATED_AT, EXPIRES_AT));
	}

	@Test
	void rateRowsAreRecordedForEveryOutcome() {
		Quote referred = QuoteFixtures.unpricedQuote(QuoteFixtures.referred(), CREATED_AT);

		assertThat(referred.getStatus()).isEqualTo(QuoteStatus.REFERRED);
		assertThat(referred.getClassRateId()).isEqualTo(1L);
		assertThat(referred.getLimitFactorId()).isEqualTo(2L);
		assertThat(referred.getRatingStateId()).isEqualTo(3L);
		assertThat(referred.getPremium()).isNull();
		assertThat(referred.getRatedInputs()).isEqualTo(QuoteFixtures.INPUTS);
	}

}
