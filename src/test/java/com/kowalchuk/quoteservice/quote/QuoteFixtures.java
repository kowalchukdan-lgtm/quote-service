package com.kowalchuk.quoteservice.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.kowalchuk.quoteservice.rating.RatingResult;
import com.kowalchuk.quoteservice.rating.RatingResult.AppliedFactor;
import com.kowalchuk.quoteservice.rating.RatingResult.Quoted;
import com.kowalchuk.quoteservice.rating.RatingResult.Reason;
import com.kowalchuk.quoteservice.rating.RatingResult.Referred;

/** Quotes built without a database, for unit tests. */
final class QuoteFixtures {

	static final RatingInputs INPUTS = new RatingInputs(LocalDate.of(2026, 11, 1), "722511", "TX",
			new BigDecimal("1000000.00"), new BigDecimal("2000000.00"), new BigDecimal("1200000.00"));

	static final Quote.RateRowIds ROWS = new Quote.RateRowIds(1L, 2L, 3L);

	private QuoteFixtures() {
	}

	static Quoted quoted(String premium, String factor) {
		return new Quoted(new BigDecimal(premium), new BigDecimal("500.00"), false,
				List.of(new AppliedFactor("CLASS", "Class of business", "722511", new BigDecimal(factor))));
	}

	static Quote quotedQuote(Instant createdAt, Instant expiresAt) {
		return Quote.create("qte_TEST", "sub_TEST", INPUTS, ROWS, quoted("2520.00", "2.1000"), "USD", createdAt,
				expiresAt);
	}

	static Quote unpricedQuote(RatingResult result, Instant createdAt) {
		return Quote.create("qte_TEST", "sub_TEST", INPUTS, ROWS, result, "USD", createdAt, null);
	}

	static Referred referred() {
		return new Referred(List.of(new Reason("REVENUE_ABOVE_AUTO_LIMIT", "Over the ceiling.")));
	}

}
