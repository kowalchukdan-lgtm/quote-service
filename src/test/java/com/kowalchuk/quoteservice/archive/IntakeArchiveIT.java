package com.kowalchuk.quoteservice.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.kowalchuk.quoteservice.OpenApiContract;
import com.kowalchuk.quoteservice.TestcontainersConfiguration;
import com.kowalchuk.quoteservice.archive.IntakeEvent.Channel;
import com.kowalchuk.quoteservice.archive.IntakeEvent.ExtractionDrafted;
import com.kowalchuk.quoteservice.archive.IntakeEvent.SubmissionReceived;
import com.kowalchuk.quoteservice.extraction.DocumentTextReader;
import com.kowalchuk.quoteservice.extraction.ExtractionPrompt;
import com.kowalchuk.quoteservice.extraction.GoldenSet;
import com.kowalchuk.quoteservice.extraction.GoldenSetScorer;
import com.kowalchuk.quoteservice.extraction.ModelClient;
import com.kowalchuk.quoteservice.extraction.ModelResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The intake archive against the real application and Postgres, with the archive and the model
 * mocked: what is archived, that it waits for the commit, and that an archive that fails or hangs
 * never affects the API.
 */
@SpringBootTest(properties = "quote.extraction.endpoint=https://ai-quote-service.test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IntakeArchiveIT {

	private static final OpenApiContract contract = OpenApiContract.load();

	private static final JsonMapper json = JsonMapper.builder().build();

	private static final GoldenSet.Case TACO_STAND = GoldenSet.load("sample-04-coastal-taco-stand");

	private static final String MODEL_ANSWER = GoldenSetScorer.perfectModelAnswer(TACO_STAND);

	@Autowired
	MockMvc mvc;

	@MockitoBean
	IntakeArchive archive;

	@MockitoBean
	ModelClient model;

	@BeforeEach
	void modelAnswersTheTacoStand() {
		when(model.complete(any())).thenReturn(new ModelResponse(MODEL_ANSWER, "gpt-5-mini-2025-08-07", 1304, 929));
	}

	@Test
	void aSubmissionIsArchivedExactlyAsReceived() throws Exception {
		// Odd spacing and key order: the archive must keep the bytes, not a re-serialised copy.
		String body = submissionRequest("Lone Star Smokehouse LLC");

		MvcResult created = createSubmission(body);

		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		String id = read(created).get("id").asString();
		SubmissionReceived received = archived(SubmissionReceived.class);
		assertThat(received.submissionId()).isEqualTo(id);
		assertThat(received.channel()).isEqualTo(Channel.API);
		assertThat(received.extractionId()).isNull();
		assertThat(received.requestJson()).isEqualTo(body);
	}

	@Test
	void aFailingArchiveDoesNotAffectTheApi() throws Exception {
		doThrow(new ArchiveWriteException(503)).when(archive).append(any());

		MvcResult created = createSubmission(submissionRequest("Failing Archive Diner LLC"));

		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		contract.assertResponseMatches("POST", "/v1/submissions", created);
		String id = read(created).get("id").asString();
		verify(archive, timeout(2000)).append(isA(SubmissionReceived.class));
		MvcResult fetched = mvc.perform(get("/v1/submissions/{id}", id)).andReturn();
		assertThat(fetched.getResponse().getStatus()).isEqualTo(200);
		assertThat(read(fetched).get("businessName").asString()).isEqualTo("Failing Archive Diner LLC");
	}

	@Test
	void aHangingArchiveDoesNotSlowTheApi() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(call -> release.await(5, TimeUnit.SECONDS)).when(archive).append(any());
		try {
			long start = System.nanoTime();
			MvcResult created = createSubmission(submissionRequest("Slow Archive Cafe LLC"));
			Duration took = Duration.ofNanos(System.nanoTime() - start);

			assertThat(created.getResponse().getStatus()).isEqualTo(201);
			// Loose enough for a slow CI runner; the archive alone would take 5 seconds.
			assertThat(took).isLessThan(Duration.ofSeconds(3));
			verify(archive, timeout(2000)).append(isA(SubmissionReceived.class));
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void anExtractionArchivesTheModelsAnswerButNotTheDocument() throws Exception {
		MvcResult uploaded = upload();

		assertThat(uploaded.getResponse().getStatus()).isEqualTo(201);
		JsonNode extraction = read(uploaded);
		ExtractionDrafted drafted = archived(ExtractionDrafted.class);
		assertThat(drafted.extractionId()).isEqualTo(extraction.get("id").asString());
		assertThat(drafted.modelResponse()).isEqualTo(MODEL_ANSWER);
		assertThat(drafted.model()).isEqualTo("gpt-5-mini-2025-08-07");
		assertThat(drafted.promptVersion()).isEqualTo(ExtractionPrompt.VERSION);
		assertThat(drafted.inputTokens()).isEqualTo(1304);
		assertThat(drafted.outputTokens()).isEqualTo(929);
		assertThat(drafted.documentSha256()).isEqualTo(extraction.at("/document/sha256").asString());
		assertThat(drafted.documentSizeBytes()).isEqualTo(TACO_STAND.pdf().length);
		// Neither the document nor its text is archived.
		String documentText = new DocumentTextReader().read(TACO_STAND.pdf());
		assertThat(drafted.toString()).doesNotContain(documentText).doesNotContain("%PDF");
	}

	@Test
	void aConfirmedDraftIsArchivedWithItsExtractionAfterCommit() throws Exception {
		String extractionId = read(upload()).get("id").asString();
		String body = submissionRequest("Coastal Taco Stand LLC");

		MvcResult confirmed = mvc.perform(post("/v1/extractions/{id}/confirm", extractionId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andReturn();

		assertThat(confirmed.getResponse().getStatus()).isEqualTo(201);
		SubmissionReceived received = archived(SubmissionReceived.class);
		assertThat(received.submissionId()).isEqualTo(read(confirmed).get("id").asString());
		assertThat(received.channel()).isEqualTo(Channel.EXTRACTION_CONFIRM);
		assertThat(received.extractionId()).isEqualTo(extractionId);
		assertThat(received.requestJson()).isEqualTo(body);
	}

	@Test
	void aRejectedConfirmArchivesNothing() throws Exception {
		String extractionId = read(upload()).get("id").asString();
		// Oil and gas drilling is not a class we write: 422, and the transaction rolls back.
		String body = submissionRequest("Coastal Taco Stand LLC").replace("722513", "213111");

		MvcResult rejected = mvc.perform(post("/v1/extractions/{id}/confirm", extractionId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andReturn();

		assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
		verify(archive, after(500).never()).append(isA(SubmissionReceived.class));
	}

	private <T extends IntakeEvent> T archived(Class<T> type) {
		ArgumentCaptor<T> event = ArgumentCaptor.forClass(type);
		verify(archive, timeout(2000)).append(isA(type));
		verify(archive, timeout(2000).atLeastOnce()).append(event.capture());
		return event.getAllValues().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
	}

	private MvcResult createSubmission(String body) throws Exception {
		return mvc.perform(post("/v1/submissions").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
	}

	private MvcResult upload() throws Exception {
		return mvc.perform(multipart("/v1/extractions")
			.file(new MockMultipartFile("document", "coastal-taco-stand.pdf", "application/pdf", TACO_STAND.pdf())))
			.andReturn();
	}

	private static String submissionRequest(String businessName) {
		String effectiveDate = LocalDate.now(ZoneOffset.UTC).plusDays(7).toString();
		return """
				{ "effectiveDate":"%s",
				  "businessName" :  "%s",
				  "address": {"line1": "1402 E 7th Ave", "city": "Tampa", "region": "FL", "postalCode": "33605", "country": "US"},
				  "classOfBusiness": {"naicsCode": "722513"},
				  "annualRevenue": {"currency": "USD", "amount": "650000.00"},
				  "requestedLimit": {"perOccurrence": {"amount": "1000000.00", "currency": "USD"},
				                     "aggregate": {"amount": "2000000.00", "currency": "USD"}}
				}
				""".formatted(effectiveDate, businessName);
	}

	private static JsonNode read(MvcResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsString());
	}

}
