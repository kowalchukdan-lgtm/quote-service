package com.kowalchuk.quoteservice.quote;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * A factor as it was charged, copied onto the quote so the quote is self-contained.
 */
@Embeddable
public record RatingFactorSnapshot(
		@Column(name = "code") String code,
		@Column(name = "description") String description,
		@Column(name = "input_value") String inputValue,
		@Column(name = "factor", precision = 10, scale = 4) BigDecimal factor) {
}
