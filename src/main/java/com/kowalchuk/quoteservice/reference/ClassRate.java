package com.kowalchuk.quoteservice.reference;

import java.math.BigDecimal;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A class of business's base rate and automatic-quoting ceiling, from a date.
 */
@Entity
@Immutable
@Table(name = "class_rates")
public class ClassRate extends EffectiveDatedRow {

	@Column(name = "naics_code", nullable = false)
	private String naicsCode;

	@Column(name = "base_rate_per_thousand", nullable = false, precision = 10, scale = 4)
	private BigDecimal baseRatePerThousand;

	@Column(name = "max_auto_revenue", nullable = false, precision = 15, scale = 2)
	private BigDecimal maxAutoRevenue;

	protected ClassRate() {
	}

	public String getNaicsCode() {
		return naicsCode;
	}

	public BigDecimal getBaseRatePerThousand() {
		return baseRatePerThousand;
	}

	public BigDecimal getMaxAutoRevenue() {
		return maxAutoRevenue;
	}

}
