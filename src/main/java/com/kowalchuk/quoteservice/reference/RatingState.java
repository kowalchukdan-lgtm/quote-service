package com.kowalchuk.quoteservice.reference;

import java.math.BigDecimal;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Values filed per state, from a date. For now, the minimum premium.
 */
@Entity
@Immutable
@Table(name = "rating_states")
public class RatingState extends EffectiveDatedRow {

	@Column(name = "state_code", nullable = false)
	private String stateCode;

	@Column(name = "minimum_premium", nullable = false, precision = 15, scale = 2)
	private BigDecimal minimumPremium;

	protected RatingState() {
	}

	public String getStateCode() {
		return stateCode;
	}

	public BigDecimal getMinimumPremium() {
		return minimumPremium;
	}

}
