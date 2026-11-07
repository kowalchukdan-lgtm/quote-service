package com.kowalchuk.quoteservice.intake;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kowalchuk.quoteservice.config.IdGenerator;
import com.kowalchuk.quoteservice.extraction.DocumentExtractor;
import com.kowalchuk.quoteservice.extraction.ExtractedSubmission;
import com.kowalchuk.quoteservice.extraction.ExtractionResult;
import com.kowalchuk.quoteservice.submission.NewSubmission;
import com.kowalchuk.quoteservice.submission.Submission;
import com.kowalchuk.quoteservice.submission.SubmissionService;

/**
 * Submission intake from documents: draft, review, confirm.
 * <p>
 * Drafting calls the AI model, which takes seconds, so it runs outside any database transaction
 * and only the save is transactional. Confirming creates the submission through
 * {@link SubmissionService}, with the same rules as any other submission, and marks the draft
 * confirmed in the same transaction: both happen or neither does.
 */
@Service
public class ExtractionService {

	private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

	static final String ID_PREFIX = "ext";

	static final int MAX_FILE_NAME = 255;

	private static final String DEFAULT_FILE_NAME = "document.pdf";

	private final ObjectProvider<DocumentExtractor> extractor;

	private final ExtractionRepository extractions;

	private final SubmissionService submissions;

	private final DraftJson draftJson;

	private final IdGenerator idGenerator;

	private final Clock clock;

	public ExtractionService(ObjectProvider<DocumentExtractor> extractor, ExtractionRepository extractions,
			SubmissionService submissions, DraftJson draftJson, IdGenerator idGenerator, Clock clock) {
		this.extractor = extractor;
		this.extractions = extractions;
		this.submissions = submissions;
		this.draftJson = draftJson;
		this.idGenerator = idGenerator;
		this.clock = clock;
	}

	/**
	 * Drafts a submission from a document and stores the draft (not the document).
	 * @throws ExtractionUnavailableException if no AI endpoint is configured
	 */
	public Extraction extract(@Nullable String fileName, byte[] document) {
		DocumentExtractor documentExtractor = extractor.getIfAvailable();
		if (documentExtractor == null) {
			throw new ExtractionUnavailableException();
		}
		ExtractionResult result = documentExtractor.extract(document);
		Instant createdAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
		Extraction extraction = extractions.save(Extraction.draft(idGenerator.next(ID_PREFIX),
				new Extraction.Drafting(safeFileName(fileName), document.length, sha256(document),
						draftJson.write(result.draft()), result.model(), result.inputTokens(), result.outputTokens(),
						result.elapsed()),
				createdAt));
		// Figures only: the draft and the document can contain policyholder data.
		log.info("Extraction {} drafted by {} in {} ms: {} input and {} output tokens, {} flags, {} fields missing",
				extraction.getId(), result.model(), result.elapsed().toMillis(), result.inputTokens(),
				result.outputTokens(), result.draft().flags().size(), result.draft().missingRequiredFields().size());
		return extraction;
	}

	@Transactional(readOnly = true)
	public Extraction get(String id) {
		return extractions.findById(id).orElseThrow(() -> new ExtractionNotFoundException(id));
	}

	public ExtractedSubmission draftOf(Extraction extraction) {
		return draftJson.read(extraction.getDraftJson());
	}

	/**
	 * Creates the submission the reviewer confirmed and marks the draft confirmed.
	 * @throws ExtractionAlreadyConfirmedException if it was already confirmed
	 * @throws com.kowalchuk.quoteservice.web.BusinessRuleViolationException if the submission
	 * breaks a rule; nothing is created and the draft stays a draft
	 */
	@Transactional
	public Submission confirm(String id, NewSubmission reviewed) {
		Extraction extraction = extractions.findForUpdateById(id).orElseThrow(() -> new ExtractionNotFoundException(id));
		if (extraction.getStatus() == ExtractionStatus.CONFIRMED) {
			throw new ExtractionAlreadyConfirmedException(id, extraction.getSubmissionId());
		}
		Submission submission = submissions.create(reviewed);
		extraction.confirm(submission.getId(), Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
		log.info("Extraction {} confirmed as submission {}", id, submission.getId());
		return submission;
	}

	static String safeFileName(@Nullable String fileName) {
		if (fileName == null || fileName.isBlank()) {
			return DEFAULT_FILE_NAME;
		}
		// Browsers may send a full path; keep the last part, without control characters.
		String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1)
			.replaceAll("\\p{Cntrl}", "")
			.strip();
		if (name.isEmpty()) {
			return DEFAULT_FILE_NAME;
		}
		return name.length() <= MAX_FILE_NAME ? name : name.substring(0, MAX_FILE_NAME);
	}

	static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available in the JDK", ex);
		}
	}

}
