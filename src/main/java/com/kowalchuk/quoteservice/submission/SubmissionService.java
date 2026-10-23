package com.kowalchuk.quoteservice.submission;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kowalchuk.quoteservice.config.IdGenerator;

@Service
public class SubmissionService {

	private static final String ID_PREFIX = "sub";

	private final SubmissionRepository submissions;

	private final SubmissionRules rules;

	private final IdGenerator idGenerator;

	private final Clock clock;

	public SubmissionService(SubmissionRepository submissions, SubmissionRules rules, IdGenerator idGenerator,
			Clock clock) {
		this.submissions = submissions;
		this.rules = rules;
		this.idGenerator = idGenerator;
		this.clock = clock;
	}

	@Transactional
	public Submission create(NewSubmission newSubmission) {
		NaicsClass naicsClass = rules.check(newSubmission);
		// Truncated to Postgres precision so the create response and a later GET agree.
		Instant createdAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
		return submissions.save(Submission.create(idGenerator.next(ID_PREFIX), newSubmission, naicsClass, createdAt));
	}

	@Transactional(readOnly = true)
	public Submission get(String id) {
		return submissions.findById(id).orElseThrow(() -> new SubmissionNotFoundException(id));
	}

}
