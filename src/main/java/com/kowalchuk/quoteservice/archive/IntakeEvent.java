package com.kowalchuk.quoteservice.archive;

import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Something that came in, kept raw for audit. Each event belongs to one entity, whose ID is the
 * archive's partition key; there is one event of each type per entity. Neither event holds a
 * broker document or its text.
 */
public sealed interface IntakeEvent {

	/** The submission or extraction the event is about. */
	String entityId();

	Type type();

	Instant occurredAt();

	enum Type {

		SUBMISSION_RECEIVED, EXTRACTION_DRAFTED

	}

	/** How a submission request arrived. */
	enum Channel {

		/** {@code POST /v1/submissions} */
		API,
		/** {@code POST /v1/extractions/{id}/confirm}, a reviewer confirming a draft */
		EXTRACTION_CONFIRM

	}

	/**
	 * A submission request, exactly as received, once the submission it created is committed.
	 * @param requestJson the request body as sent (UTF-8), not re-serialised
	 * @param extractionId the draft it confirms, for {@link Channel#EXTRACTION_CONFIRM}
	 */
	record SubmissionReceived(String submissionId, Instant occurredAt, Channel channel,
			@Nullable String extractionId, String requestJson) implements IntakeEvent {

		@Override
		public String entityId() {
			return submissionId;
		}

		@Override
		public Type type() {
			return Type.SUBMISSION_RECEIVED;
		}

	}

	/**
	 * The model's answer for an extraction, as returned, once the draft is saved.
	 * @param modelResponse the model's raw JSON answer; it includes the model's short evidence
	 * quotes, which the stored draft holds too
	 * @param documentSha256 a fingerprint of the document; the document itself isn't kept
	 */
	record ExtractionDrafted(String extractionId, Instant occurredAt, String model, String promptVersion,
			int inputTokens, int outputTokens, Duration elapsed, String modelResponse, String documentSha256,
			long documentSizeBytes) implements IntakeEvent {

		@Override
		public String entityId() {
			return extractionId;
		}

		@Override
		public Type type() {
			return Type.EXTRACTION_DRAFTED;
		}

	}

}
