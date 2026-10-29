package com.kowalchuk.quoteservice.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kowalchuk.quoteservice.config.IdGenerator;
import com.kowalchuk.quoteservice.rating.RatingEngine;
import com.kowalchuk.quoteservice.rating.RatingInput;
import com.kowalchuk.quoteservice.reference.ClassRateRepository;
import com.kowalchuk.quoteservice.reference.LimitFactorRepository;
import com.kowalchuk.quoteservice.reference.RatingStateRepository;
import com.kowalchuk.quoteservice.submission.Address;
import com.kowalchuk.quoteservice.submission.Money;
import com.kowalchuk.quoteservice.submission.NaicsClass;
import com.kowalchuk.quoteservice.submission.NewSubmission;
import com.kowalchuk.quoteservice.submission.Submission;
import com.kowalchuk.quoteservice.submission.SubmissionNotFoundException;
import com.kowalchuk.quoteservice.submission.SubmissionRepository;
import com.kowalchuk.quoteservice.web.BusinessRuleViolationException;

/**
 * The quote-time business rules in {@link QuoteService}, with every collaborator mocked.
 */
class QuoteServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-23T14:05:13Z");

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

	private final SubmissionRepository submissions = mock(SubmissionRepository.class);

	private final RatingEngine engine = mock(RatingEngine.class);

	private final QuoteRepository quotes = mock(QuoteRepository.class);

	private final QuoteService service = new QuoteService(submissions, mock(ClassRateRepository.class),
			mock(LimitFactorRepository.class), mock(RatingStateRepository.class), engine, quotes, new IdGenerator(),
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void pastEffectiveDateIsABusinessRuleViolationAndNothingIsRated() {
		submissionEffective("sub_PAST", TODAY.minusDays(1));

		BusinessRuleViolationException ex = catchThrowableOfType(BusinessRuleViolationException.class,
				() -> service.create("sub_PAST"));

		assertThat(ex).isNotNull();
		assertThat(ex.getViolations()).singleElement().satisfies(violation -> {
			assertThat(violation.pointer()).isEmpty();
			assertThat(violation.detail()).contains("sub_PAST").contains("2026-10-22").contains("is in the past");
		});
		verifyNoInteractions(engine, quotes);
	}

	@Test
	void unknownSubmissionIsNotFound() {
		when(submissions.findById("sub_MISSING")).thenReturn(Optional.empty());

		assertThatExceptionOfType(SubmissionNotFoundException.class).isThrownBy(() -> service.create("sub_MISSING"))
			.withMessage("No submission with ID 'sub_MISSING' was found.");
		verifyNoInteractions(engine, quotes);
	}

	@Test
	void effectiveDateTodayIsAccepted() {
		submissionEffective("sub_TODAY", TODAY);
		when(engine.rate(any(RatingInput.class))).thenReturn(QuoteFixtures.referred());
		when(quotes.save(any(Quote.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Quote quote = service.create("sub_TODAY");

		assertThat(quote.getStatus()).isEqualTo(QuoteStatus.REFERRED);
		verify(quotes).save(quote);
	}

	private void submissionEffective(String id, LocalDate effectiveDate) {
		NewSubmission newSubmission = new NewSubmission("Lone Star Smokehouse LLC",
				new Address("1200 Congress Ave", null, "Austin", "TX", "78701", "US"), "722511",
				new Money(new BigDecimal("1200000.00"), "USD"), new Money(new BigDecimal("1000000.00"), "USD"),
				new Money(new BigDecimal("2000000.00"), "USD"), effectiveDate);
		Submission submission = Submission.create(id, newSubmission,
				new NaicsClass("722511", "Full-Service Restaurants", true), NOW);
		when(submissions.findById(id)).thenReturn(Optional.of(submission));
	}

}
