package com.kowalchuk.quoteservice.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kowalchuk.quoteservice.config.IdGenerator;
import com.kowalchuk.quoteservice.rating.RatingEngine;
import com.kowalchuk.quoteservice.reference.ClassRate;
import com.kowalchuk.quoteservice.reference.ClassRateRepository;
import com.kowalchuk.quoteservice.reference.LimitFactor;
import com.kowalchuk.quoteservice.reference.LimitFactorRepository;
import com.kowalchuk.quoteservice.reference.RatingState;
import com.kowalchuk.quoteservice.reference.RatingStateRepository;
import com.kowalchuk.quoteservice.submission.Address;
import com.kowalchuk.quoteservice.submission.Money;
import com.kowalchuk.quoteservice.submission.NaicsClass;
import com.kowalchuk.quoteservice.submission.NewSubmission;
import com.kowalchuk.quoteservice.submission.Submission;
import com.kowalchuk.quoteservice.submission.SubmissionRepository;

/**
 * Expiry is 30 × 24 elapsed hours as an instant, set only on priced quotes.
 */
class QuoteServiceExpiryTest {

	// The contract's example. The US daylight-saving change on 2026-11-01 falls before expiry.
	private static final Instant CREATED_AT = Instant.parse("2026-10-23T14:05:13Z");

	private static final LocalDate EFFECTIVE_DATE = LocalDate.of(2026, 11, 1);

	private final SubmissionRepository submissions = mock(SubmissionRepository.class);

	private final ClassRateRepository classRates = mock(ClassRateRepository.class);

	private final LimitFactorRepository limitFactors = mock(LimitFactorRepository.class);

	private final RatingStateRepository ratingStates = mock(RatingStateRepository.class);

	private final QuoteRepository quotes = mock(QuoteRepository.class);

	private final QuoteService service = new QuoteService(submissions, classRates, limitFactors, ratingStates,
			new RatingEngine(), quotes, new IdGenerator(), Clock.fixed(CREATED_AT, ZoneOffset.UTC));

	@BeforeEach
	void rates() {
		ClassRate classRate = mock(ClassRate.class);
		when(classRate.getId()).thenReturn(1L);
		when(classRate.getBaseRatePerThousand()).thenReturn(new BigDecimal("2.1000"));
		when(classRate.getMaxAutoRevenue()).thenReturn(new BigDecimal("5000000.00"));
		when(classRates.findInEffect("722511", EFFECTIVE_DATE)).thenReturn(Optional.of(classRate));

		LimitFactor limitFactor = mock(LimitFactor.class);
		when(limitFactor.getId()).thenReturn(2L);
		when(limitFactor.getFactor()).thenReturn(new BigDecimal("1.0000"));
		when(limitFactors.findInEffect(any(BigDecimal.class), any(BigDecimal.class), any(LocalDate.class)))
			.thenReturn(Optional.of(limitFactor));

		RatingState ratingState = mock(RatingState.class);
		when(ratingState.getId()).thenReturn(3L);
		when(ratingState.getMinimumPremium()).thenReturn(new BigDecimal("500.00"));
		when(ratingStates.findInEffect("TX", EFFECTIVE_DATE)).thenReturn(Optional.of(ratingState));

		when(quotes.save(any(Quote.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void pricedQuoteExpiresExactly720HoursLater() {
		Quote quote = service.create(submission("sub_PRICED", "1200000.00", true));

		assertThat(quote.getStatus()).isEqualTo(QuoteStatus.QUOTED);
		assertThat(quote.getCreatedAt()).isEqualTo(CREATED_AT);
		assertThat(quote.getExpiresAt()).isEqualTo(Instant.parse("2026-11-22T14:05:13Z"));
		assertThat(Duration.between(quote.getCreatedAt(), quote.getExpiresAt())).isEqualTo(Duration.ofHours(720));
	}

	@Test
	void referredQuoteHasNoExpiry() {
		Quote quote = service.create(submission("sub_REFERRED", "6000000.00", true));

		assertThat(quote.getStatus()).isEqualTo(QuoteStatus.REFERRED);
		assertThat(quote.getExpiresAt()).isNull();
	}

	@Test
	void declinedQuoteHasNoExpiry() {
		Quote quote = service.create(submission("sub_DECLINED", "1200000.00", false));

		assertThat(quote.getStatus()).isEqualTo(QuoteStatus.DECLINED);
		assertThat(quote.getExpiresAt()).isNull();
	}

	private String submission(String id, String revenue, boolean inAppetite) {
		NewSubmission newSubmission = new NewSubmission("Lone Star Smokehouse LLC",
				new Address("1200 Congress Ave", null, "Austin", "TX", "78701", "US"), "722511",
				new Money(new BigDecimal(revenue), "USD"), new Money(new BigDecimal("1000000.00"), "USD"),
				new Money(new BigDecimal("2000000.00"), "USD"), EFFECTIVE_DATE);
		NaicsClass naicsClass = new NaicsClass("722511", "Full-Service Restaurants", inAppetite);
		when(submissions.findById(id))
			.thenReturn(Optional.of(Submission.create(id, newSubmission, naicsClass, CREATED_AT)));
		return id;
	}

}
