package com.kowalchuk.quoteservice.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import com.kowalchuk.quoteservice.archive.IntakeEvent;
import com.kowalchuk.quoteservice.config.IdGenerator;
import com.kowalchuk.quoteservice.extraction.DocumentExtractor;
import com.kowalchuk.quoteservice.extraction.ExtractionResult;
import com.kowalchuk.quoteservice.submission.ReceivedRequest;
import com.kowalchuk.quoteservice.submission.Submission;
import com.kowalchuk.quoteservice.submission.SubmissionService;
import com.kowalchuk.quoteservice.web.BusinessRuleViolationException;
import com.kowalchuk.quoteservice.web.FieldViolation;

class ExtractionServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-07T17:10:00.123456789Z");

	private final DocumentExtractor documentExtractor = mock(DocumentExtractor.class);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<DocumentExtractor> extractorProvider = mock(ObjectProvider.class);

	private final ExtractionRepository repository = mock(ExtractionRepository.class);

	private final SubmissionService submissions = mock(SubmissionService.class);

	private final IdGenerator ids = mock(IdGenerator.class);

	private final DraftJson draftJson = new DraftJson();

	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final ExtractionService service = new ExtractionService(extractorProvider, repository, submissions,
			draftJson, ids, Clock.fixed(NOW, ZoneOffset.UTC), events);

	@Test
	void storesTheDraftAndTheDocumentsFingerprintButNotTheDocument() {
		byte[] pdf = "%PDF-1.4 taco".getBytes();
		when(extractorProvider.getIfAvailable()).thenReturn(documentExtractor);
		when(documentExtractor.extract(pdf)).thenReturn(new ExtractionResult(ExtractionFixtures.tacoStandDraft(),
				"gpt-5-mini-2025-08-07", 1304, 929, Duration.ofMillis(8400), "{\"businessName\":\"Coastal Taco Stand LLC\"}",
				"sha256:0123456789ab"));
		when(ids.next("ext")).thenReturn("ext_1");
		when(repository.save(any(Extraction.class))).thenAnswer(call -> call.getArgument(0));

		Extraction extraction = service.extract("C:\\Users\\broker\\taco.pdf", pdf);

		assertThat(extraction.getId()).isEqualTo("ext_1");
		assertThat(extraction.getStatus()).isEqualTo(ExtractionStatus.DRAFT);
		assertThat(extraction.getDocumentName()).isEqualTo("taco.pdf");
		assertThat(extraction.getDocumentSizeBytes()).isEqualTo(pdf.length);
		assertThat(extraction.getDocumentSha256()).isEqualTo(ExtractionService.sha256(pdf)).hasSize(64);
		assertThat(extraction.getModel()).isEqualTo("gpt-5-mini-2025-08-07");
		assertThat(extraction.getInputTokens()).isEqualTo(1304);
		assertThat(extraction.getOutputTokens()).isEqualTo(929);
		assertThat(extraction.getDurationMs()).isEqualTo(8400);
		assertThat(extraction.getCreatedAt()).isEqualTo(Instant.parse("2026-11-07T17:10:00.123456Z"));
		assertThat(service.draftOf(extraction)).isEqualTo(ExtractionFixtures.tacoStandDraft());
		assertThat(extraction.getDraftJson()).doesNotContain("%PDF");
	}

	@Test
	void archivesTheModelsAnswerButNotTheDocument() {
		byte[] pdf = "%PDF-1.4 taco".getBytes();
		when(extractorProvider.getIfAvailable()).thenReturn(documentExtractor);
		when(documentExtractor.extract(pdf)).thenReturn(new ExtractionResult(ExtractionFixtures.tacoStandDraft(),
				"gpt-5-mini-2025-08-07", 1304, 929, Duration.ofMillis(8400), "{\"businessName\":\"Coastal Taco Stand LLC\"}",
				"sha256:0123456789ab"));
		when(ids.next("ext")).thenReturn("ext_1");
		when(repository.save(any(Extraction.class))).thenAnswer(call -> call.getArgument(0));

		service.extract("taco.pdf", pdf);

		ArgumentCaptor<IntakeEvent.ExtractionDrafted> event = ArgumentCaptor.forClass(IntakeEvent.ExtractionDrafted.class);
		verify(events).publishEvent(event.capture());
		assertThat(event.getValue()).isEqualTo(new IntakeEvent.ExtractionDrafted("ext_1",
				Instant.parse("2026-11-07T17:10:00.123456Z"), "gpt-5-mini-2025-08-07", "sha256:0123456789ab", 1304, 929,
				Duration.ofMillis(8400), "{\"businessName\":\"Coastal Taco Stand LLC\"}", ExtractionService.sha256(pdf),
				pdf.length));
		assertThat(event.getValue().toString()).doesNotContain("%PDF");
	}

	@Test
	void isUnavailableWhenNoModelIsConfigured() {
		when(extractorProvider.getIfAvailable()).thenReturn(null);

		assertThatThrownBy(() -> service.extract("taco.pdf", new byte[] { 1 }))
			.isInstanceOf(ExtractionUnavailableException.class);
		verify(repository, never()).save(any());
	}

	@Test
	void confirmingCreatesTheSubmissionAndRecordsIt() {
		Extraction draft = ExtractionFixtures.draftExtraction("ext_1");
		Submission submission = ExtractionFixtures.submission("sub_1");
		when(repository.findForUpdateById("ext_1")).thenReturn(Optional.of(draft));
		when(submissions.create(ExtractionFixtures.reviewedSubmission(), new ReceivedRequest("{ }", "ext_1")))
			.thenReturn(submission);

		Submission created = service.confirm("ext_1", ExtractionFixtures.reviewedSubmission(), "{ }");

		assertThat(created).isSameAs(submission);
		assertThat(draft.getStatus()).isEqualTo(ExtractionStatus.CONFIRMED);
		assertThat(draft.getSubmissionId()).isEqualTo("sub_1");
		assertThat(draft.getConfirmedAt()).isEqualTo(Instant.parse("2026-11-07T17:10:00.123456Z"));
	}

	@Test
	void confirmingTwiceIsAConflictAndCreatesNothing() {
		Extraction confirmed = ExtractionFixtures.draftExtraction("ext_1");
		confirmed.confirm("sub_1", NOW);
		when(repository.findForUpdateById("ext_1")).thenReturn(Optional.of(confirmed));

		assertThatThrownBy(() -> service.confirm("ext_1", ExtractionFixtures.reviewedSubmission()))
			.isInstanceOf(ExtractionAlreadyConfirmedException.class)
			.hasMessage("Extraction ext_1 was already confirmed as submission sub_1.");
		verify(submissions, never()).create(any(), any());
	}

	@Test
	void aSubmissionThatBreaksARuleLeavesTheDraftUnconfirmed() {
		Extraction draft = ExtractionFixtures.draftExtraction("ext_1");
		when(repository.findForUpdateById("ext_1")).thenReturn(Optional.of(draft));
		when(submissions.create(any(), any())).thenThrow(new BusinessRuleViolationException(
				List.of(new FieldViolation("/annualRevenue/amount", "Annual revenue must be greater than zero."))));

		assertThatThrownBy(() -> service.confirm("ext_1", ExtractionFixtures.reviewedSubmission()))
			.isInstanceOf(BusinessRuleViolationException.class);
		assertThat(draft.getStatus()).isEqualTo(ExtractionStatus.DRAFT);
		assertThat(draft.getSubmissionId()).isNull();
	}

	@Test
	void anUnknownIdIsNotFound() {
		when(repository.findById("ext_NOPE")).thenReturn(Optional.empty());
		when(repository.findForUpdateById("ext_NOPE")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get("ext_NOPE")).isInstanceOf(ExtractionNotFoundException.class)
			.hasMessage("No extraction with ID 'ext_NOPE' was found.");
		assertThatThrownBy(() -> service.confirm("ext_NOPE", ExtractionFixtures.reviewedSubmission()))
			.isInstanceOf(ExtractionNotFoundException.class);
	}

	@Test
	void anExtractionCanOnlyBeConfirmedOnceEvenDirectly() {
		Extraction extraction = ExtractionFixtures.draftExtraction("ext_1");
		extraction.confirm("sub_1", NOW);

		assertThatThrownBy(() -> extraction.confirm("sub_2", NOW))
			.isInstanceOf(ExtractionAlreadyConfirmedException.class);
		assertThat(extraction.getSubmissionId()).isEqualTo("sub_1");
	}

	@Test
	void keepsOnlyASafeFileName() {
		assertThat(ExtractionService.safeFileName(null)).isEqualTo("document.pdf");
		assertThat(ExtractionService.safeFileName("  ")).isEqualTo("document.pdf");
		assertThat(ExtractionService.safeFileName("/tmp/uploads/")).isEqualTo("document.pdf");
		assertThat(ExtractionService.safeFileName("../../etc/app.pdf")).isEqualTo("app.pdf");
		assertThat(ExtractionService.safeFileName("taco\u0000\n.pdf")).isEqualTo("taco.pdf");
		assertThat(ExtractionService.safeFileName("x".repeat(300) + ".pdf")).hasSize(ExtractionService.MAX_FILE_NAME);
	}

	@Test
	void fingerprintsWithSha256() {
		assertThat(ExtractionService.sha256("abc".getBytes()))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

}
