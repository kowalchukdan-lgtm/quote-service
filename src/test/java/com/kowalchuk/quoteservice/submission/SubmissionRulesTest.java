package com.kowalchuk.quoteservice.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.jpa.domain.Specification;

import com.kowalchuk.quoteservice.reference.LimitFactor;
import com.kowalchuk.quoteservice.reference.LimitFactorRepository;
import com.kowalchuk.quoteservice.web.BusinessRuleViolationException;
import com.kowalchuk.quoteservice.web.FieldViolation;

class SubmissionRulesTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

	private static final NaicsClass RESTAURANTS = new NaicsClass("722511", "Full-Service Restaurants", true);

	private static final NaicsClass DRILLING = new NaicsClass("213111", "Drilling Oil and Gas Wells", false);

	private final NaicsClassRepository naicsClasses = mock(NaicsClassRepository.class);

	private final LimitFactorRepository limitFactors = mock(LimitFactorRepository.class);

	// Late in the UTC day, to show "today" is the UTC date.
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T23:59:00Z"), ZoneOffset.UTC);

	private final SubmissionRules rules = new SubmissionRules(clock, naicsClasses, limitFactors);

	@BeforeEach
	void naicsClasses() {
		when(naicsClasses.findById("722511")).thenReturn(Optional.of(RESTAURANTS));
		when(naicsClasses.findById("213111")).thenReturn(Optional.of(DRILLING));
		when(naicsClasses.findById("999999")).thenReturn(Optional.empty());
		// The rows in effect on the policy date; the date filtering itself is covered by
		// RateLookupRepositoryTest and the API tests against Postgres.
		offeredInEffect(limitFactor("1000000.00", "2000000.00"), limitFactor("2000000.00", "4000000.00"));
	}

	@Test
	void validSubmissionPassesAndResolvesTheNaicsClass() {
		assertThat(rules.check(submission())).isSameAs(RESTAURANTS);
	}

	@ParameterizedTest(name = "today {0} days: accepted={1}")
	@CsvSource({ "-1, false", "0, true", "90, true", "91, false" })
	void effectiveDateMustBeWithinTheNextNinetyDays(int daysFromToday, boolean accepted) {
		NewSubmission submission = submission(TODAY.plusDays(daysFromToday), "722511",
				usd("1000000.00"), usd("2000000.00"));
		if (accepted) {
			assertThat(rules.check(submission)).isSameAs(RESTAURANTS);
		}
		else {
			assertThat(violations(submission)).extracting(FieldViolation::pointer).containsExactly("/effectiveDate");
		}
	}

	@Test
	void limitPairsCompareByValueNotScale() {
		assertThat(rules.check(submission(TODAY, "722511", usd("1000000"), usd("2000000.00"))))
			.isSameAs(RESTAURANTS);
		assertThat(rules.check(submission(TODAY, "722511", usd("2000000.0"), usd("4000000"))))
			.isSameAs(RESTAURANTS);
	}

	@Test
	void unofferedLimitPairIsRejected() {
		assertThat(violations(submission(TODAY, "722511", usd("1000000.00"), usd("3000000.00"))))
			.extracting(FieldViolation::pointer)
			.containsExactly("/requestedLimit");
	}

	@Test
	void unofferedLimitPairListsTheOfferedCombinations() {
		assertThat(violations(submission(TODAY, "722511", usd("1000000.00"), usd("3000000.00"))))
			.singleElement()
			.extracting(FieldViolation::detail)
			.isEqualTo("Limits 1000000.00 / 3000000.00 are not offered. "
					+ "Offered combinations: 1,000,000 / 2,000,000 and 2,000,000 / 4,000,000.");
	}

	@Test
	void pairWithNoFactorInEffectOnThePolicyDateIsRejected() {
		// 2,000,000 / 4,000,000 only takes effect after this policy starts.
		offeredInEffect(limitFactor("1000000.00", "2000000.00"));

		assertThat(violations(submission(TODAY, "722511", usd("2000000.00"), usd("4000000.00"))))
			.singleElement()
			.satisfies(violation -> {
				assertThat(violation.pointer()).isEqualTo("/requestedLimit");
				assertThat(violation.detail()).endsWith("Offered combinations: 1,000,000 / 2,000,000.");
			});
	}

	@Test
	void correctionRowsForOnePairListItOnce() {
		offeredInEffect(limitFactor("1000000.00", "2000000.00"), limitFactor("1000000", "2000000"));

		assertThat(violations(submission(TODAY, "722511", usd("2000000.00"), usd("4000000.00"))))
			.singleElement()
			.extracting(FieldViolation::detail)
			.asString()
			.endsWith("Offered combinations: 1,000,000 / 2,000,000.");
	}

	@Test
	void classOutsideAppetiteIsRejectedWithItsTitle() {
		assertThat(violations(submission(TODAY, "213111", usd("1000000.00"), usd("2000000.00"))))
			.singleElement()
			.satisfies(violation -> {
				assertThat(violation.pointer()).isEqualTo("/classOfBusiness/naicsCode");
				assertThat(violation.detail()).contains("Drilling Oil and Gas Wells").contains("outside appetite");
			});
	}

	@Test
	void everyViolationIsReportedTogether() {
		NewSubmission submission = new NewSubmission("Lone Star Smokehouse LLC",
				new Address("1200 Congress Ave", null, "Toronto", "ON", "78701", "CA"), "999999",
				new Money(BigDecimal.ZERO, "EUR"), new Money(new BigDecimal("1000000.00"), "CAD"),
				new Money(new BigDecimal("2000000.00"), "USD"), TODAY.minusDays(1));

		assertThat(violations(submission)).extracting(FieldViolation::pointer)
			.containsExactlyInAnyOrder("/effectiveDate", "/annualRevenue/currency",
					"/requestedLimit/perOccurrence/currency", "/address/country", "/annualRevenue/amount",
					"/classOfBusiness/naicsCode");
	}

	@SuppressWarnings("unchecked")
	private void offeredInEffect(LimitFactor... rows) {
		when(limitFactors.findAll(any(Specification.class))).thenReturn(List.of(rows));
	}

	private static LimitFactor limitFactor(String perOccurrence, String aggregate) {
		LimitFactor factor = mock(LimitFactor.class);
		when(factor.getPerOccurrenceLimit()).thenReturn(new BigDecimal(perOccurrence));
		when(factor.getAggregateLimit()).thenReturn(new BigDecimal(aggregate));
		return factor;
	}

	private List<FieldViolation> violations(NewSubmission submission) {
		BusinessRuleViolationException ex = catchThrowableOfType(BusinessRuleViolationException.class,
				() -> rules.check(submission));
		assertThat(ex).as("expected a business rule violation").isNotNull();
		return ex.getViolations();
	}

	private static NewSubmission submission() {
		return submission(TODAY.plusDays(9), "722511", usd("1000000.00"), usd("2000000.00"));
	}

	private static NewSubmission submission(LocalDate effectiveDate, String naicsCode, Money perOccurrence,
			Money aggregate) {
		return new NewSubmission("Lone Star Smokehouse LLC",
				new Address("1200 Congress Ave", null, "Austin", "TX", "78701", "US"), naicsCode,
				usd("1200000.00"), perOccurrence, aggregate, effectiveDate);
	}

	private static Money usd(String amount) {
		return new Money(new BigDecimal(amount), "USD");
	}

}
