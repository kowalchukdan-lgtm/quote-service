package com.kowalchuk.quoteservice.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.kowalchuk.quoteservice.rating.RatingResult.AppliedFactor;
import com.kowalchuk.quoteservice.rating.RatingResult.Declined;
import com.kowalchuk.quoteservice.rating.RatingResult.Quoted;
import com.kowalchuk.quoteservice.rating.RatingResult.Reason;
import com.kowalchuk.quoteservice.rating.RatingResult.Referred;

/**
 * Rates a risk: declines it, refers it, or prices it. The formula is fictional and simplified;
 * see the README.
 * <p>
 * Plain Java with no framework or database dependencies, so every rule is unit-testable and
 * the same inputs always give the same result. Choosing which rates are in effect is the
 * caller's job; this class prices whatever it is given.
 */
public final class RatingEngine {

	/** Identifies this formula. Bump it in the same commit as any change to how premium is calculated. */
	public static final String PLAN_VERSION = "GL-DEMO-1";

	/** Rates are per $1,000 of revenue; must match {@code class_rates.base_rate_per_thousand}. */
	public static final BigDecimal RATE_UNIT = new BigDecimal("1000");

	// Rounding rules (mode, precision, and whether any intermediate rounding applies) come
	// from the filed rating manual in production. This reference implementation follows the
	// contract: half-up to cents, once, at the end of rating. Intermediates are exact.
	public static final int PREMIUM_SCALE = 2;

	public static final RoundingMode PREMIUM_ROUNDING = RoundingMode.HALF_UP;

	// Dividing by RATE_UNIT is a decimal-point shift, which is exact and cannot throw.
	private static final int RATE_UNIT_EXPONENT = RATE_UNIT.precision() - RATE_UNIT.scale() - 1;

	static {
		if (BigDecimal.ONE.movePointRight(RATE_UNIT_EXPONENT).compareTo(RATE_UNIT) != 0) {
			throw new IllegalStateException("RATE_UNIT must be a power of ten: " + RATE_UNIT);
		}
	}

	public RatingResult rate(RatingInput input) {
		if (!input.classInAppetite()) {
			return new Declined(List.of(new Reason("CLASS_NOT_WRITTEN",
					"We do not currently write NAICS " + input.naicsCode() + ".")));
		}

		List<Reason> referrals = new ArrayList<>();
		ClassRating classRating = input.classRating();
		if (classRating == null) {
			referrals.add(new Reason("NO_RATE_FOR_CLASS",
					"No base rate for NAICS " + input.naicsCode() + " is in effect on " + input.ratingDate() + "."));
		}
		if (input.limitFactor() == null) {
			referrals.add(new Reason("LIMIT_NOT_RATED", "No limit factor for " + money(input.perOccurrenceLimit())
					+ " / " + money(input.aggregateLimit()) + " is in effect on " + input.ratingDate() + "."));
		}
		if (input.minimumPremium() == null) {
			referrals.add(new Reason("NO_MINIMUM_FOR_STATE",
					"No minimum premium for " + input.stateCode() + " is in effect on " + input.ratingDate() + "."));
		}
		if (classRating != null && input.annualRevenue().compareTo(classRating.maxAutoRevenue()) > 0) {
			referrals.add(new Reason("REVENUE_ABOVE_AUTO_LIMIT",
					"Annual revenue " + money(input.annualRevenue()) + " exceeds the automatic quoting limit of "
							+ money(classRating.maxAutoRevenue()) + " for NAICS " + input.naicsCode() + "."));
		}
		if (!referrals.isEmpty()) {
			return new Referred(referrals);
		}

		// All three are non-null here: a missing one was a referral above.
		BigDecimal limitFactor = input.limitFactor();
		BigDecimal minimumPremium = input.minimumPremium();

		BigDecimal exposureUnits = input.annualRevenue().movePointLeft(RATE_UNIT_EXPONENT);
		BigDecimal raw = exposureUnits.multiply(classRating.baseRatePerThousand()).multiply(limitFactor);
		boolean minimumApplied = raw.compareTo(minimumPremium) < 0;
		BigDecimal premium = raw.max(minimumPremium).setScale(PREMIUM_SCALE, PREMIUM_ROUNDING);

		return new Quoted(premium, minimumPremium, minimumApplied, List.of(
				new AppliedFactor("CLASS", "Class of business", input.naicsCode(), classRating.baseRatePerThousand()),
				new AppliedFactor("LIMIT", "Limit combination",
						plain(input.perOccurrenceLimit()) + "/" + plain(input.aggregateLimit()), limitFactor)));
	}

	// 6000000 -> "6,000,000.00". UNNECESSARY: money has at most two decimals, so nothing is rounded.
	private static String money(BigDecimal amount) {
		return String.format(Locale.US, "%,.2f", amount.setScale(2, RoundingMode.UNNECESSARY));
	}

	// 1000000.00 -> "1000000", the format of the contract's LIMIT example.
	private static String plain(BigDecimal amount) {
		return amount.stripTrailingZeros().toPlainString();
	}

}
