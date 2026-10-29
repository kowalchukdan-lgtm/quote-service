package com.kowalchuk.quoteservice.rating;

import java.math.BigDecimal;
import java.util.List;

/**
 * The outcome of rating. Sealed, so a {@code switch} over it must handle every outcome.
 */
public sealed interface RatingResult {

	/**
	 * Priced.
	 *
	 * @param premium the annual premium, rounded once (see {@link RatingEngine#PREMIUM_ROUNDING})
	 * @param minimumPremium the state minimum that applied as a floor
	 * @param minimumApplied whether the floor set the premium
	 * @param factors the factors in the order applied
	 */
	record Quoted(BigDecimal premium, BigDecimal minimumPremium, boolean minimumApplied, List<AppliedFactor> factors)
			implements RatingResult {

		public Quoted {
			factors = List.copyOf(factors);
		}

	}

	/** Needs underwriter review; not priced. */
	record Referred(List<Reason> reasons) implements RatingResult {

		public Referred {
			reasons = List.copyOf(reasons);
		}

	}

	/** Outside appetite; not priced. */
	record Declined(List<Reason> reasons) implements RatingResult {

		public Declined {
			reasons = List.copyOf(reasons);
		}

	}

	/** A factor applied during rating, as the contract's {@code RatingFactor} describes it. */
	record AppliedFactor(String code, String description, String inputValue, BigDecimal factor) {
	}

	/** Why a risk was referred or declined, with the values compared in {@code message}. */
	record Reason(String code, String message) {
	}

}
