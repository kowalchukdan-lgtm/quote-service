package com.kowalchuk.quoteservice.quote;

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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.domain.Specification;

import com.kowalchuk.quoteservice.config.IdGenerator;
import com.kowalchuk.quoteservice.rating.RatingEngine;
import com.kowalchuk.quoteservice.rating.RatingInput;
import com.kowalchuk.quoteservice.reference.ClassRateRepository;
import com.kowalchuk.quoteservice.reference.LimitFactor;
import com.kowalchuk.quoteservice.reference.LimitFactorRepository;
import com.kowalchuk.quoteservice.reference.RatingStateRepository;
import com.kowalchuk.quoteservice.submission.Address;
import com.kowalchuk.quoteservice.submission.EffectiveDateRule;
import com.kowalchuk.quoteservice.submission.Money;
import com.kowalchuk.quoteservice.submission.NaicsClass;
import com.kowalchuk.quoteservice.submission.NaicsClassRepository;
import com.kowalchuk.quoteservice.submission.NewSubmission;
import com.kowalchuk.quoteservice.submission.Submission;
import com.kowalchuk.quoteservice.submission.SubmissionRepository;
import com.kowalchuk.quoteservice.submission.SubmissionRules;
import com.kowalchuk.quoteservice.web.BusinessRuleViolationException;
import com.kowalchuk.quoteservice.web.FieldViolation;

/**
 * Submission creation and quoting share one effective-date rule, so for the same date and the
 * same clock they must agree: both accept, or both reject with the same message. Only the
 * pointer differs, because only the submission request has a body.
 */
class EffectiveDateConsistencyTest {

	private static final Instant NOW = Instant.parse("2026-10-23T14:05:13Z");

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

	private static final NaicsClass RESTAURANTS = new NaicsClass("722511", "Full-Service Restaurants", true);

	private final EffectiveDateRule rule = new EffectiveDateRule(Clock.fixed(NOW, ZoneOffset.UTC));

	@ParameterizedTest(name = "today {0} days")
	@ValueSource(ints = { -1, 0, 1 })
	@SuppressWarnings("unchecked")
	void submissionRulesAndQuoteServiceGiveTheSameAnswer(int daysFromToday) {
		NewSubmission newSubmission = newSubmission(TODAY.plusDays(daysFromToday));

		NaicsClassRepository naicsClasses = mock(NaicsClassRepository.class);
		when(naicsClasses.findById("722511")).thenReturn(Optional.of(RESTAURANTS));
		LimitFactorRepository limitFactors = mock(LimitFactorRepository.class);
		LimitFactor offered = mock(LimitFactor.class);
		when(offered.getPerOccurrenceLimit()).thenReturn(new BigDecimal("1000000.00"));
		when(offered.getAggregateLimit()).thenReturn(new BigDecimal("2000000.00"));
		when(limitFactors.findAll(any(Specification.class))).thenReturn(List.of(offered));
		SubmissionRules submissionRules = new SubmissionRules(rule, naicsClasses, limitFactors);

		SubmissionRepository submissions = mock(SubmissionRepository.class);
		when(submissions.findById("sub_TEST"))
			.thenReturn(Optional.of(Submission.create("sub_TEST", newSubmission, RESTAURANTS, NOW)));
		RatingEngine engine = mock(RatingEngine.class);
		when(engine.rate(any(RatingInput.class))).thenReturn(QuoteFixtures.referred());
		QuoteRepository quotes = mock(QuoteRepository.class);
		when(quotes.save(any(Quote.class))).thenAnswer(invocation -> invocation.getArgument(0));
		QuoteService quoteService = new QuoteService(submissions, mock(ClassRateRepository.class), limitFactors,
				mock(RatingStateRepository.class), engine, quotes, new IdGenerator(), rule,
				Clock.fixed(NOW, ZoneOffset.UTC));

		Optional<FieldViolation> atSubmission = violation(() -> submissionRules.check(newSubmission));
		Optional<FieldViolation> atQuote = violation(() -> quoteService.create("sub_TEST"));

		assertThat(atQuote.isPresent()).as("both callers reject, or both accept").isEqualTo(atSubmission.isPresent());
		assertThat(atSubmission.isPresent()).as("only yesterday is in the past").isEqualTo(daysFromToday < 0);
		assertThat(atQuote.map(FieldViolation::detail)).isEqualTo(atSubmission.map(FieldViolation::detail));
		atSubmission.ifPresent(violation -> assertThat(violation.pointer()).isEqualTo("/effectiveDate"));
		atQuote.ifPresent(violation -> assertThat(violation.pointer()).isEmpty());
	}

	private static Optional<FieldViolation> violation(Runnable call) {
		BusinessRuleViolationException ex = catchThrowableOfType(BusinessRuleViolationException.class, call::run);
		if (ex == null) {
			return Optional.empty();
		}
		assertThat(ex.getViolations()).as("only the effective date is wrong").hasSize(1);
		return Optional.of(ex.getViolations().get(0));
	}

	private static NewSubmission newSubmission(LocalDate effectiveDate) {
		return new NewSubmission("Lone Star Smokehouse LLC",
				new Address("1200 Congress Ave", null, "Austin", "TX", "78701", "US"), "722511",
				new Money(new BigDecimal("1200000.00"), "USD"), new Money(new BigDecimal("1000000.00"), "USD"),
				new Money(new BigDecimal("2000000.00"), "USD"), effectiveDate);
	}

}
