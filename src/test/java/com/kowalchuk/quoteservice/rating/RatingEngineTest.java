package com.kowalchuk.quoteservice.rating;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.kowalchuk.quoteservice.rating.RatingResult.AppliedFactor;
import com.kowalchuk.quoteservice.rating.RatingResult.Declined;
import com.kowalchuk.quoteservice.rating.RatingResult.Quoted;
import com.kowalchuk.quoteservice.rating.RatingResult.Reason;
import com.kowalchuk.quoteservice.rating.RatingResult.Referred;

class RatingEngineTest {

	private static final LocalDate RATING_DATE = LocalDate.of(2026, 12, 1);

	private static final ClassRating RESTAURANTS = rating("2.1000", "5000000.00");

	private final RatingEngine engine = new RatingEngine();

	//Hand Written
	@Test
    void quotesStandardRiskAtExpectedPremium_handWritten() {
        // Arrange
        RatingEngine engine = new RatingEngine();

        ClassRating classRating = new ClassRating(
                new BigDecimal("2.0000"),        // baseRatePerThousand
                new BigDecimal("5000000.00"));   // maxAutoRevenue

        RatingInput input = new RatingInput(
                new BigDecimal("1000000.00"),    // annualRevenue
                "722511",                        // naicsCode: full-service restaurants
                "PA",                            // stateCode
                LocalDate.of(2026, 12, 1),       // ratingDate: the policy effective date
                true,                            // classInAppetite
                classRating,                     // classRating
                new BigDecimal("1000000.00"),    // perOccurrenceLimit
                new BigDecimal("2000000.00"),    // aggregateLimit
                new BigDecimal("1.2500"),        // limitFactor
                new BigDecimal("500.00"));       // minimumPremium

        // Act
        RatingResult result = engine.rate(input);   // check the method name in RatingEngine

        // Assert: it's quoted, at the premium calculated by hand
        assertThat(result).isInstanceOfSatisfying(RatingResult.Quoted.class, quoted -> {
            assertThat(quoted.premium()).isEqualByComparingTo(new BigDecimal("2500.00"));
            assertThat(quoted.premium().scale()).isEqualTo(2);
            assertThat(quoted.minimumApplied()).isFalse();
            assertThat(quoted.minimumPremium()).isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(quoted.factors()).isNotEmpty();
        });
    }

	@Test
	void pricesTheContractsRestaurantExample() {
		// 1,200,000 / 1,000 × 2.1000 × 1.0000 = 2520.00
		Quoted quoted = quoted(input("1200000.00", RESTAURANTS, "1.0000", "500.00"));

		assertThat(quoted.premium()).isEqualTo(new BigDecimal("2520.00"));
		assertThat(quoted.minimumApplied()).isFalse();
		assertThat(quoted.minimumPremium()).isEqualByComparingTo("500.00");
		assertThat(quoted.factors()).containsExactly(
				new AppliedFactor("CLASS", "Class of business", "722511", new BigDecimal("2.1000")),
				new AppliedFactor("LIMIT", "Limit combination", "1000000/2000000", new BigDecimal("1.0000")));
	}

	@Test
	void minimumPremiumIsAFloor() {
		// 150,000 / 1,000 × 1.4000 = 210.00, below the 500.00 minimum
		Quoted quoted = quoted(input("150000.00", rating("1.4000", "1000000.00"), "1.0000", "500.00"));

		assertThat(quoted.premium()).isEqualTo(new BigDecimal("500.00"));
		assertThat(quoted.minimumApplied()).isTrue();
	}

	@Test
	void theMinimumPassedInIsTheOneUsed() {
		Quoted quoted = quoted(input("150000.00", rating("1.4000", "1000000.00"), "1.0000", "750.00"));

		assertThat(quoted.premium()).isEqualTo(new BigDecimal("750.00"));
		assertThat(quoted.minimumPremium()).isEqualByComparingTo("750.00");
	}

	@Test
	void exactHalfCentRoundsUp() {
		// 1000.005 exactly: HALF_UP gives 1000.01, HALF_EVEN would give 1000.00.
		Quoted quoted = quoted(input("1000005.00", rating("1.0000", "5000000.00"), "1.0000", "100.00"));

		assertThat(quoted.premium()).isEqualTo(new BigDecimal("1000.01"));
	}

	@Test
	void roundsOnceAtTheEndNotAtEachStep() {
		// Exact: 333.335 × 1.35 = 450.00225 -> 450.00.
		// Rounding the base first would give 333.34 × 1.35 = 450.009 -> 450.01.
		Quoted quoted = quoted(input("333335.00", rating("1.0000", "5000000.00"), "1.3500", "100.00"));

		assertThat(quoted.premium()).isEqualTo(new BigDecimal("450.00"));
	}

	@Test
	void revenueScaleDoesNotChangeThePremium() {
		Quoted withoutDecimals = quoted(input("1200000", RESTAURANTS, "1.0000", "500.00"));
		Quoted withDecimals = quoted(input("1200000.00", RESTAURANTS, "1.0000", "500.00"));

		assertThat(withoutDecimals.premium()).isEqualTo(withDecimals.premium()).isEqualTo(new BigDecimal("2520.00"));
	}

	@Test
	void everyPremiumHasExactlyTwoDecimals() {
		assertThat(quoted(input("1200000", RESTAURANTS, "1", "500")).premium().scale()).isEqualTo(2);
		assertThat(quoted(input("150000", rating("1.4", "1000000"), "1", "500")).premium().scale()).isEqualTo(2);
		assertThat(quoted(input("1234567.89", RESTAURANTS, "1.3500", "500.00")).premium().scale()).isEqualTo(2);
	}

	@Test
	void roundingRulesAreHalfUpToCents() {
		assertThat(RatingEngine.PREMIUM_ROUNDING).isEqualTo(RoundingMode.HALF_UP);
		assertThat(RatingEngine.PREMIUM_SCALE).isEqualTo(2);
		assertThat(RatingEngine.RATE_UNIT).isEqualByComparingTo("1000");
	}

	@Test
	void outOfAppetiteIsDeclinedAndWinsOverReferral() {
		RatingResult result = engine.rate(new RatingInput(new BigDecimal("6000000.00"), "722511", "TX", RATING_DATE,
				false, null, new BigDecimal("1000000.00"), new BigDecimal("2000000.00"), null, null));

		assertThat(result).isInstanceOfSatisfying(Declined.class, declined -> assertThat(declined.reasons())
			.extracting(Reason::code)
			.containsExactly("CLASS_NOT_WRITTEN"));
	}

	@Test
	void everyMissingRateIsAReferralReason() {
		RatingResult result = engine.rate(input("1200000.00", null, null, null));

		assertThat(result).isInstanceOfSatisfying(Referred.class, referred -> {
			assertThat(referred.reasons()).extracting(Reason::code)
				.containsExactly("NO_RATE_FOR_CLASS", "LIMIT_NOT_RATED", "NO_MINIMUM_FOR_STATE");
			assertThat(referred.reasons()).extracting(Reason::message)
				.containsExactly("No base rate for NAICS 722511 is in effect on 2026-12-01.",
						"No limit factor for 1,000,000.00 / 2,000,000.00 is in effect on 2026-12-01.",
						"No minimum premium for TX is in effect on 2026-12-01.");
		});
	}

	@Test
	void revenueAboveTheCeilingIsReferredWithBothValues() {
		RatingResult result = engine.rate(input("6000000.00", RESTAURANTS, null, "500.00"));

		assertThat(result).isInstanceOfSatisfying(Referred.class, referred -> assertThat(referred.reasons())
			.containsExactly(
					new Reason("LIMIT_NOT_RATED",
							"No limit factor for 1,000,000.00 / 2,000,000.00 is in effect on 2026-12-01."),
					new Reason("REVENUE_ABOVE_AUTO_LIMIT", "Annual revenue 6,000,000.00 exceeds the automatic "
							+ "quoting limit of 5,000,000.00 for NAICS 722511.")));
	}

	@Test
	void revenueEqualToTheCeilingIsQuoted() {
		assertThat(engine.rate(input("5000000.00", RESTAURANTS, "1.0000", "500.00"))).isInstanceOf(Quoted.class);
	}

	/**
	 * Every seeded class against both offered limit pairs at 1,000,000 revenue. The rates repeat
	 * the V3 seed data on purpose, as hand-calculated golden values.
	 */
	@ParameterizedTest(name = "{0} at {2}/{3}: {5}")
	@CsvSource({
			"722511, 2.1000, 1000000.00, 2000000.00, 1.0000, 2100.00",
			"722511, 2.1000, 2000000.00, 4000000.00, 1.3500, 2835.00",
			"722513, 1.8000, 1000000.00, 2000000.00, 1.0000, 1800.00",
			"722513, 1.8000, 2000000.00, 4000000.00, 1.3500, 2430.00",
			"238220, 4.5000, 1000000.00, 2000000.00, 1.0000, 4500.00",
			"238220, 4.5000, 2000000.00, 4000000.00, 1.3500, 6075.00",
			"541511, 0.6000, 1000000.00, 2000000.00, 1.0000, 600.00",
			"541511, 0.6000, 2000000.00, 4000000.00, 1.3500, 810.00",
			"812111, 1.4000, 1000000.00, 2000000.00, 1.0000, 1400.00",
			"812111, 1.4000, 2000000.00, 4000000.00, 1.3500, 1890.00" })
	void pricesEverySeededClassAtBothLimits(String naicsCode, String baseRate, String perOccurrence, String aggregate,
			String limitFactor, String expectedPremium) {
		RatingInput input = new RatingInput(new BigDecimal("1000000.00"), naicsCode, "TX", RATING_DATE, true,
				rating(baseRate, "10000000.00"), new BigDecimal(perOccurrence), new BigDecimal(aggregate),
				new BigDecimal(limitFactor), new BigDecimal("500.00"));

		Quoted quoted = quoted(input);

		assertThat(quoted.premium()).isEqualTo(new BigDecimal(expectedPremium));
		assertThat(quoted.premium().scale()).isEqualTo(2);
		assertThat(quoted.factors()).extracting(AppliedFactor::code).containsExactly("CLASS", "LIMIT");
		assertThat(quoted.factors().get(0).inputValue()).isEqualTo(naicsCode);
		assertThat(quoted.factors().get(1).inputValue())
			.isEqualTo(new BigDecimal(perOccurrence).toBigInteger() + "/" + new BigDecimal(aggregate).toBigInteger());
	}

	@ParameterizedTest(name = "revenue {0}: premium {1}, minimum applied {2}")
	@CsvSource({
			"499990.00, 500.00, true",
			// 499.996 is below the minimum even though it would round to 500.00.
			"499996.00, 500.00, true",
			// Equal is not below.
			"500000.00, 500.00, false",
			"500010.00, 500.01, false" })
	void minimumPremiumBoundary(String revenue, String expectedPremium, boolean expectedMinimumApplied) {
		Quoted quoted = quoted(input(revenue, rating("1.0000", "5000000.00"), "1.0000", "500.00"));

		assertThat(quoted.premium()).isEqualTo(new BigDecimal(expectedPremium));
		assertThat(quoted.minimumApplied()).isEqualTo(expectedMinimumApplied);
	}

	@ParameterizedTest(name = "revenue {0} -> {1}")
	@CsvSource({
			// Tie, even kept digit: HALF_EVEN would give 1000.00.
			"1000005.00, 1000.01",
			// Tie, odd kept digit: both modes agree.
			"1000015.00, 1000.02",
			// Just below half a cent.
			"1000004.99, 1000.00",
			// Just above half a cent.
			"1000005.01, 1000.01",
			// Tiny raw value (0.00001): the minimum applies.
			"0.01, 100.00" })
	void roundsHalfUpToCents(String revenue, String expectedPremium) {
		Quoted quoted = quoted(input(revenue, rating("1.0000", "5000000.00"), "1.0000", "100.00"));

		assertThat(quoted.premium()).isEqualTo(new BigDecimal(expectedPremium));
	}

	@Test
	void highPrecisionInputsAreExactUntilTheSingleRounding() {
		// Expected value computed independently: an exact division, two multiplications, one rounding.
		BigDecimal expected = new BigDecimal("1234567.89").divide(new BigDecimal("1000"))
			.multiply(new BigDecimal("1.2345"))
			.multiply(new BigDecimal("1.3333"))
			.setScale(2, RoundingMode.HALF_UP);

		Quoted quoted = quoted(input("1234567.89", rating("1.2345", "5000000.00"), "1.3333", "100.00"));

		assertThat(quoted.premium()).isEqualTo(expected);
	}

	private Quoted quoted(RatingInput input) {
		RatingResult result = engine.rate(input);
		assertThat(result).isInstanceOf(Quoted.class);
		return (Quoted) result;
	}

	private static RatingInput input(String revenue, @Nullable ClassRating classRating, @Nullable String limitFactor,
			@Nullable String minimumPremium) {
		return new RatingInput(new BigDecimal(revenue), "722511", "TX", RATING_DATE, true, classRating,
				new BigDecimal("1000000.00"), new BigDecimal("2000000.00"),
				(limitFactor != null) ? new BigDecimal(limitFactor) : null,
				(minimumPremium != null) ? new BigDecimal(minimumPremium) : null);
	}

	private static ClassRating rating(String baseRate, String ceiling) {
		return new ClassRating(new BigDecimal(baseRate), new BigDecimal(ceiling));
	}

}
