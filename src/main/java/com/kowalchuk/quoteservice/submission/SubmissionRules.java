package com.kowalchuk.quoteservice.submission;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.kowalchuk.quoteservice.web.BusinessRuleViolationException;
import com.kowalchuk.quoteservice.web.FieldViolation;

/**
 * Business rules for new submissions (the contract's 422 rules). These may change without a
 * contract version change, so they live here rather than in the schema or the database.
 * <p>
 * All rules run, and every violation is reported together.
 */
@Component
public class SubmissionRules {

	static final int MAX_DAYS_AHEAD = 90;

	private static final String ACCEPTED_CURRENCY = "USD";

	private static final String ACCEPTED_COUNTRY = "US";

	private static final List<LimitPair> OFFERED_LIMITS = List.of(
			new LimitPair(new BigDecimal("1000000"), new BigDecimal("2000000")),
			new LimitPair(new BigDecimal("2000000"), new BigDecimal("4000000")));

	private final Clock clock;

	private final NaicsClassRepository naicsClasses;

	public SubmissionRules(Clock clock, NaicsClassRepository naicsClasses) {
		this.clock = clock;
		this.naicsClasses = naicsClasses;
	}

	/**
	 * Checks every rule and returns the resolved NAICS class.
	 * @throws BusinessRuleViolationException listing every rule the submission breaks
	 */
	public NaicsClass check(NewSubmission submission) {
		List<FieldViolation> violations = new ArrayList<>();

		// "Today" is the UTC date. Whether it should follow the broker's or the risk's
		// time zone is an open question.
		LocalDate today = LocalDate.now(clock);
		LocalDate effectiveDate = submission.effectiveDate();
		if (effectiveDate.isBefore(today)) {
			violations.add(new FieldViolation("/effectiveDate",
					"Effective date " + effectiveDate + " is in the past."));
		}
		else if (effectiveDate.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
			violations.add(new FieldViolation("/effectiveDate",
					"Effective date " + effectiveDate + " is more than " + MAX_DAYS_AHEAD + " days ahead."));
		}

		checkCurrency(submission.annualRevenue(), "/annualRevenue/currency", violations);
		checkCurrency(submission.perOccurrenceLimit(), "/requestedLimit/perOccurrence/currency", violations);
		checkCurrency(submission.aggregateLimit(), "/requestedLimit/aggregate/currency", violations);

		String country = submission.address().country();
		if (!ACCEPTED_COUNTRY.equals(country)) {
			violations.add(new FieldViolation("/address/country",
					"Country " + country + " is not accepted; only US addresses are supported."));
		}

		if (submission.annualRevenue().amount().signum() <= 0) {
			violations.add(new FieldViolation("/annualRevenue/amount", "Annual revenue must be greater than zero."));
		}

		BigDecimal perOccurrence = submission.perOccurrenceLimit().amount();
		BigDecimal aggregate = submission.aggregateLimit().amount();
		if (OFFERED_LIMITS.stream().noneMatch(pair -> pair.matches(perOccurrence, aggregate))) {
			violations.add(new FieldViolation("/requestedLimit",
					"Limits " + perOccurrence.toPlainString() + " / " + aggregate.toPlainString()
							+ " are not offered. Offered combinations: 1,000,000 / 2,000,000 and 2,000,000 / 4,000,000."));
		}

		String naicsCode = submission.naicsCode();
		Optional<NaicsClass> naicsClass = naicsClasses.findById(naicsCode);
		if (naicsClass.isEmpty()) {
			violations.add(new FieldViolation("/classOfBusiness/naicsCode",
					"NAICS " + naicsCode + " is not a class we write."));
		}
		else if (!naicsClass.get().isInAppetite()) {
			violations.add(new FieldViolation("/classOfBusiness/naicsCode", "NAICS " + naicsCode + " ("
					+ naicsClass.get().getTitle() + ") is outside appetite for this product."));
		}

		if (!violations.isEmpty()) {
			throw new BusinessRuleViolationException(violations);
		}
		return naicsClass.get();
	}

	private static void checkCurrency(Money money, String pointer, List<FieldViolation> violations) {
		if (!ACCEPTED_CURRENCY.equals(money.currency())) {
			violations.add(new FieldViolation(pointer,
					"Currency " + money.currency() + " is not accepted; only USD is supported."));
		}
	}

	private record LimitPair(BigDecimal perOccurrence, BigDecimal aggregate) {

		// compareTo, not equals: 1000000 and 1000000.00 are the same limit.
		boolean matches(BigDecimal requestedPerOccurrence, BigDecimal requestedAggregate) {
			return perOccurrence.compareTo(requestedPerOccurrence) == 0
					&& aggregate.compareTo(requestedAggregate) == 0;
		}

	}

}
