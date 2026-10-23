package com.kowalchuk.quoteservice.submission;

import java.math.BigDecimal;

import jakarta.persistence.Embeddable;

/**
 * A monetary amount. Exact decimal in Java and NUMERIC(15,2) in the database; column names
 * are set per use with {@code @AttributeOverride}. Compare amounts with
 * {@link BigDecimal#compareTo}, never {@code equals}, which also compares scale.
 */
@Embeddable
public record Money(BigDecimal amount, String currency) {
}
