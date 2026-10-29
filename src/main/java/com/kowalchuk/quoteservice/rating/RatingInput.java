package com.kowalchuk.quoteservice.rating;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.jspecify.annotations.Nullable;

/**
 * Everything {@link RatingEngine} needs, as plain values. The caller has already selected the
 * rates in effect on {@code ratingDate}; a null rate means no row was in effect.
 *
 * @param ratingDate the policy effective date the rates were selected by; used only in messages
 */
public record RatingInput(
		BigDecimal annualRevenue,
		String naicsCode,
		String stateCode,
		LocalDate ratingDate,
		boolean classInAppetite,
		@Nullable ClassRating classRating,
		BigDecimal perOccurrenceLimit,
		BigDecimal aggregateLimit,
		@Nullable BigDecimal limitFactor,
		@Nullable BigDecimal minimumPremium) {
}
