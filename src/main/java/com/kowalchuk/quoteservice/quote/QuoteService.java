package com.kowalchuk.quoteservice.quote;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kowalchuk.quoteservice.config.IdGenerator;
import com.kowalchuk.quoteservice.rating.ClassRating;
import com.kowalchuk.quoteservice.rating.RatingEngine;
import com.kowalchuk.quoteservice.rating.RatingInput;
import com.kowalchuk.quoteservice.rating.RatingResult;
import com.kowalchuk.quoteservice.reference.ClassRate;
import com.kowalchuk.quoteservice.reference.ClassRateRepository;
import com.kowalchuk.quoteservice.reference.EffectiveDatedRow;
import com.kowalchuk.quoteservice.reference.LimitFactor;
import com.kowalchuk.quoteservice.reference.LimitFactorRepository;
import com.kowalchuk.quoteservice.reference.RatingState;
import com.kowalchuk.quoteservice.reference.RatingStateRepository;
import com.kowalchuk.quoteservice.submission.EffectiveDateRule;
import com.kowalchuk.quoteservice.submission.Submission;
import com.kowalchuk.quoteservice.submission.SubmissionNotFoundException;
import com.kowalchuk.quoteservice.submission.SubmissionRepository;
import com.kowalchuk.quoteservice.web.BusinessRuleViolationException;
import com.kowalchuk.quoteservice.web.FieldViolation;

@Service
public class QuoteService {

	private static final String ID_PREFIX = "qte";

	// Quotes are valid for exactly 30 × 24 hours from createdAt, as an instant. This is
	// deliberately not "the end of the 30th day" in any time zone; see README "Quote expiry".
	// A constant, not config: a business rule changed by reviewed code; becomes effective-dated reference data (chosen by quote creation date) once it varies by state or product.
	static final Duration VALIDITY = Duration.ofDays(30);

	private final SubmissionRepository submissions;

	private final ClassRateRepository classRates;

	private final LimitFactorRepository limitFactors;

	private final RatingStateRepository ratingStates;

	private final RatingEngine engine;

	private final QuoteRepository quotes;

	private final IdGenerator idGenerator;

	private final EffectiveDateRule effectiveDateRule;

	private final Clock clock;

	public QuoteService(SubmissionRepository submissions, ClassRateRepository classRates,
			LimitFactorRepository limitFactors, RatingStateRepository ratingStates, RatingEngine engine,
			QuoteRepository quotes, IdGenerator idGenerator, EffectiveDateRule effectiveDateRule, Clock clock) {
		this.submissions = submissions;
		this.classRates = classRates;
		this.limitFactors = limitFactors;
		this.ratingStates = ratingStates;
		this.engine = engine;
		this.quotes = quotes;
		this.idGenerator = idGenerator;
		this.effectiveDateRule = effectiveDateRule;
		this.clock = clock;
	}

	@Transactional
	public Quote create(String submissionId) {
		Submission submission = submissions.findById(submissionId)
			.orElseThrow(() -> new SubmissionNotFoundException(submissionId));

		// The same rule as at submission time. A past effective date means the request cannot be
		// processed; it says nothing about the risk, so it is a 422, not a decline. The pointer is
		// "" because a quote request has no body.
		LocalDate effectiveDate = submission.getEffectiveDate();
		effectiveDateRule.pastDateViolation(effectiveDate).ifPresent(message -> {
			throw new BusinessRuleViolationException(List.of(new FieldViolation("", message)));
		});

		// Rates are selected by the policy effective date, not the date of the quote: a filing
		// applies to policies effective on or after its date, and a quote can be requested up to
		// 90 days before cover starts.
		LocalDate ratingDate = effectiveDate;
		String naicsCode = submission.getNaicsClass().getCode();
		// The rating state is the risk address's state (Address is the risk location in the
		// contract, not a mailing address). The contract allows one location per submission;
		// multi-state risks are not supported.
		String stateCode = submission.getAddress().region();
		RatingInputs inputs = new RatingInputs(ratingDate, naicsCode, stateCode,
				submission.getPerOccurrenceLimit().amount(), submission.getAggregateLimit().amount(),
				submission.getAnnualRevenue().amount());

		Optional<ClassRate> classRate = classRates.findInEffect(naicsCode, ratingDate);
		Optional<LimitFactor> limitFactor = limitFactors.findInEffect(inputs.perOccurrenceLimit(),
				inputs.aggregateLimit(), ratingDate);
		Optional<RatingState> ratingState = ratingStates.findInEffect(stateCode, ratingDate);

		RatingResult result = engine.rate(new RatingInput(inputs.annualRevenue(), naicsCode, stateCode, ratingDate,
				submission.getNaicsClass().isInAppetite(),
				classRate.map(rate -> new ClassRating(rate.getBaseRatePerThousand(), rate.getMaxAutoRevenue()))
					.orElse(null),
				inputs.perOccurrenceLimit(), inputs.aggregateLimit(),
				limitFactor.map(LimitFactor::getFactor).orElse(null),
				ratingState.map(RatingState::getMinimumPremium).orElse(null)));

		// Truncated to Postgres precision so the create response and a later GET agree.
		Instant createdAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
		Instant expiresAt = (result instanceof RatingResult.Quoted) ? createdAt.plus(VALIDITY) : null;
		// Recorded for every outcome, so referrals and declines can be explained too.
		Quote.RateRowIds rateRows = new Quote.RateRowIds(id(classRate), id(limitFactor), id(ratingState));

		return quotes.save(Quote.create(idGenerator.next(ID_PREFIX), submissionId, inputs, rateRows, result,
				submission.getAnnualRevenue().currency(), createdAt, expiresAt));
	}

	@Transactional(readOnly = true)
	public Quote get(String quoteId) {
		return quotes.findById(quoteId).orElseThrow(() -> new QuoteNotFoundException(quoteId));
	}

	private static @Nullable Long id(Optional<? extends EffectiveDatedRow> row) {
		return row.map(EffectiveDatedRow::getId).orElse(null);
	}

}
