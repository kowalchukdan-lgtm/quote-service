package com.kowalchuk.quoteservice.submission;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kowalchuk.quoteservice.archive.IntakeEvent;
import com.kowalchuk.quoteservice.config.IdGenerator;

@Service
public class SubmissionService {

	private static final String ID_PREFIX = "sub";

	private final SubmissionRepository submissions;

	private final SubmissionRules rules;

	private final IdGenerator idGenerator;

	private final Clock clock;

	private final ApplicationEventPublisher events;

	public SubmissionService(SubmissionRepository submissions, SubmissionRules rules, IdGenerator idGenerator,
			Clock clock, ApplicationEventPublisher events) {
		this.submissions = submissions;
		this.rules = rules;
		this.idGenerator = idGenerator;
		this.clock = clock;
		this.events = events;
	}

	@Transactional
	public Submission create(NewSubmission newSubmission) {
		return create(newSubmission, null);
	}

	/**
	 * Creates the submission and, if {@code received} is given, archives the request as it
	 * arrived. The archive is written only after the outermost transaction commits.
	 */
	@Transactional
	public Submission create(NewSubmission newSubmission, @Nullable ReceivedRequest received) {
		NaicsClass naicsClass = rules.check(newSubmission);
		// Truncated to Postgres precision so the create response and a later GET agree.
		Instant createdAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
		Submission submission = submissions
			.save(Submission.create(idGenerator.next(ID_PREFIX), newSubmission, naicsClass, createdAt));
		if (received != null) {
			IntakeEvent.Channel channel = received.extractionId() == null ? IntakeEvent.Channel.API
					: IntakeEvent.Channel.EXTRACTION_CONFIRM;
			events.publishEvent(new IntakeEvent.SubmissionReceived(submission.getId(), createdAt, channel,
					received.extractionId(), received.json()));
		}
		return submission;
	}

	@Transactional(readOnly = true)
	public Submission get(String id) {
		return submissions.findById(id).orElseThrow(() -> new SubmissionNotFoundException(id));
	}

}
