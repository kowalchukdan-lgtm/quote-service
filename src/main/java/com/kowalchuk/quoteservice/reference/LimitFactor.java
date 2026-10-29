package com.kowalchuk.quoteservice.reference;

import java.math.BigDecimal;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The factor for an offered limit combination, from a date. A pair with a factor in effect is
 * an offered pair.
 */
@Entity
@Immutable
@Table(name = "limit_factors")
public class LimitFactor extends EffectiveDatedRow {

	@Column(name = "per_occurrence_limit", nullable = false, precision = 15, scale = 2)
	private BigDecimal perOccurrenceLimit;

	@Column(name = "aggregate_limit", nullable = false, precision = 15, scale = 2)
	private BigDecimal aggregateLimit;

	@Column(nullable = false, precision = 10, scale = 4)
	private BigDecimal factor;

	protected LimitFactor() {
	}

	public BigDecimal getPerOccurrenceLimit() {
		return perOccurrenceLimit;
	}

	public BigDecimal getAggregateLimit() {
		return aggregateLimit;
	}

	public BigDecimal getFactor() {
		return factor;
	}

}
