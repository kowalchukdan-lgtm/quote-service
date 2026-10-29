package com.kowalchuk.quoteservice.submission;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.kowalchuk.quoteservice.reference.EffectiveDatedRepository;
import com.kowalchuk.quoteservice.reference.LimitFactorRepository;
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

	// How far ahead a policy can start. The README's "Changing a rate" section suggests loading
	// new rates about this far ahead of their effective date, where the filing timeline allows.
	static final int MAX_DAYS_AHEAD = 90;

	private static final String ACCEPTED_CURRENCY = "USD";

	private static final String ACCEPTED_COUNTRY = "US";

	private final Clock clock;

	private final NaicsClassRepository naicsClasses;

	private final LimitFactorRepository limitFactors;

	public SubmissionRules(Clock clock, NaicsClassRepository naicsClasses, LimitFactorRepository limitFactors) {
		this.clock = clock;
		this.naicsClasses = naicsClasses;
		this.limitFactors = limitFactors;
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

		// limit_factors is the only list of offered pairs: a pair is offered if it has a factor in
		// effect on the policy effective date, so every accepted submission can be rated.
		BigDecimal perOccurrence = submission.perOccurrenceLimit().amount();
		BigDecimal aggregate = submission.aggregateLimit().amount();
		List<LimitPair> offered = offeredLimits(effectiveDate);
		if (offered.stream().noneMatch(pair -> pair.matches(perOccurrence, aggregate))) {
			violations.add(new FieldViolation("/requestedLimit", "Limits " + perOccurrence.toPlainString() + " / "
					+ aggregate.toPlainString() + " are not offered. " + describe(offered, effectiveDate)));
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

	private List<LimitPair> offeredLimits(LocalDate effectiveDate) {
		return limitFactors.findAll(EffectiveDatedRepository.effectiveOnOrBefore(effectiveDate))
			.stream()
			.map(factor -> new LimitPair(factor.getPerOccurrenceLimit(), factor.getAggregateLimit()))
			.distinct()
			.sorted(Comparator.comparing(LimitPair::perOccurrence).thenComparing(LimitPair::aggregate))
			.toList();
	}

	private static String describe(List<LimitPair> offered, LocalDate effectiveDate) {
		if (offered.isEmpty()) {
			return "No limit combinations are offered for policies effective " + effectiveDate + ".";
		}
		List<String> pairs = offered.stream().map(LimitPair::label).toList();
		String joined = (pairs.size() == 1) ? pairs.get(0)
				: String.join(", ", pairs.subList(0, pairs.size() - 1)) + " and " + pairs.get(pairs.size() - 1);
		return "Offered combinations: " + joined + ".";
	}

	private record LimitPair(BigDecimal perOccurrence, BigDecimal aggregate) {

		// Normalised so equals (and distinct) treat 1000000 and 1000000.00 as the same limit.
		LimitPair {
			perOccurrence = perOccurrence.stripTrailingZeros();
			aggregate = aggregate.stripTrailingZeros();
		}

		// compareTo, not equals: the requested amounts are not normalised.
		boolean matches(BigDecimal requestedPerOccurrence, BigDecimal requestedAggregate) {
			return perOccurrence.compareTo(requestedPerOccurrence) == 0
					&& aggregate.compareTo(requestedAggregate) == 0;
		}

		// "1,000,000 / 2,000,000"
		String label() {
			return group(perOccurrence) + " / " + group(aggregate);
		}

		private static String group(BigDecimal amount) {
			DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
			return format.format(amount);
		}

	}

}
