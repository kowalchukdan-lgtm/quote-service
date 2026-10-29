package com.kowalchuk.quoteservice.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The inputs a quote was rated with and the keys its rate lookups used. They repeat the
 * (immutable) submission on purpose, so a quote explains itself, including why no rate row was
 * found when a lookup came back empty.
 *
 * @param asOf the policy effective date the rates were selected by
 */
@Embeddable
public record RatingInputs(
		@Column(name = "rated_as_of") LocalDate asOf,
		@Column(name = "rated_naics_code") String naicsCode,
		@Column(name = "rated_state_code") String stateCode,
		@Column(name = "rated_per_occurrence_limit", precision = 15, scale = 2) BigDecimal perOccurrenceLimit,
		@Column(name = "rated_aggregate_limit", precision = 15, scale = 2) BigDecimal aggregateLimit,
		@Column(name = "rated_annual_revenue", precision = 15, scale = 2) BigDecimal annualRevenue) {
}
