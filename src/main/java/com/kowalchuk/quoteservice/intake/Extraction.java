package com.kowalchuk.quoteservice.intake;

import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A draft submission read from a broker document, and its review state. It changes once: when
 * a person confirms it, it records the submission that was created. Everything else is fixed
 * when it is drafted.
 * <p>
 * The draft is stored as JSON ({@link DraftJson}); the document itself is not stored.
 */
@Entity
@Table(name = "extractions")
public class Extraction implements Persistable<String> {

	@Id
	private String id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ExtractionStatus status;

	@Column(name = "document_name", nullable = false)
	private String documentName;

	@Column(name = "document_size_bytes", nullable = false)
	private int documentSizeBytes;

	@Column(name = "document_sha256", nullable = false)
	private String documentSha256;

	@Column(name = "draft_json", nullable = false, columnDefinition = "text")
	private String draftJson;

	@Column(nullable = false)
	private String model;

	@Column(name = "input_tokens", nullable = false)
	private int inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private int outputTokens;

	@Column(name = "duration_ms", nullable = false)
	private long durationMs;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "submission_id")
	private @Nullable String submissionId;

	@Column(name = "confirmed_at")
	private @Nullable Instant confirmedAt;

	@Transient
	private boolean persisted;

	protected Extraction() {
	}

	/** The facts about a drafting run, as recorded with the draft. */
	public record Drafting(String documentName, int documentSizeBytes, String documentSha256, String draftJson,
			String model, int inputTokens, int outputTokens, Duration elapsed) {
	}

	public static Extraction draft(String id, Drafting drafting, Instant createdAt) {
		Extraction extraction = new Extraction();
		extraction.id = id;
		extraction.status = ExtractionStatus.DRAFT;
		extraction.documentName = drafting.documentName();
		extraction.documentSizeBytes = drafting.documentSizeBytes();
		extraction.documentSha256 = drafting.documentSha256();
		extraction.draftJson = drafting.draftJson();
		extraction.model = drafting.model();
		extraction.inputTokens = drafting.inputTokens();
		extraction.outputTokens = drafting.outputTokens();
		extraction.durationMs = drafting.elapsed().toMillis();
		extraction.createdAt = createdAt;
		return extraction;
	}

	/**
	 * Records the submission a reviewer created from this draft.
	 * @throws ExtractionAlreadyConfirmedException if it was already confirmed
	 */
	public void confirm(String submissionId, Instant confirmedAt) {
		if (status == ExtractionStatus.CONFIRMED) {
			throw new ExtractionAlreadyConfirmedException(id, this.submissionId);
		}
		this.status = ExtractionStatus.CONFIRMED;
		this.submissionId = submissionId;
		this.confirmedAt = confirmedAt;
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return !persisted;
	}

	@PostPersist
	@PostLoad
	void markPersisted() {
		this.persisted = true;
	}

	public ExtractionStatus getStatus() {
		return status;
	}

	public String getDocumentName() {
		return documentName;
	}

	public int getDocumentSizeBytes() {
		return documentSizeBytes;
	}

	public String getDocumentSha256() {
		return documentSha256;
	}

	public String getDraftJson() {
		return draftJson;
	}

	public String getModel() {
		return model;
	}

	public int getInputTokens() {
		return inputTokens;
	}

	public int getOutputTokens() {
		return outputTokens;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public @Nullable String getSubmissionId() {
		return submissionId;
	}

	public @Nullable Instant getConfirmedAt() {
		return confirmedAt;
	}

}
