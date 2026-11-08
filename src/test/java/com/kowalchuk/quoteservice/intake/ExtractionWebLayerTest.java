package com.kowalchuk.quoteservice.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.kowalchuk.quoteservice.OpenApiContract;
import com.kowalchuk.quoteservice.config.ClockConfig;
import com.kowalchuk.quoteservice.config.JacksonConfig;
import com.kowalchuk.quoteservice.extraction.ExtractionFailedException;
import com.kowalchuk.quoteservice.extraction.UnreadableDocumentException;
import com.kowalchuk.quoteservice.submission.SubmissionMapper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The extraction endpoints with the service mocked: status codes, headers and Problem Details for
 * every outcome, including ones that are hard to cause end to end (a model failure, an upload
 * over the size limit). Every response is checked against the contract.
 */
@Tag("slice")
@WebMvcTest(controllers = ExtractionController.class)
@Import({ ExtractionMapper.class, SubmissionMapper.class, ClockConfig.class, JacksonConfig.class })
class ExtractionWebLayerTest {

	private static final String EXTRACTIONS = "/v1/extractions";

	private static final String EXTRACTION = "/v1/extractions/{extractionId}";

	private static final String CONFIRM = "/v1/extractions/{extractionId}/confirm";

	private static final String PROBLEM_BASE = "https://api.example.com/problems/";

	private static final OpenApiContract contract = OpenApiContract.load();

	private static final JsonMapper json = JsonMapper.builder().build();

	private static final byte[] PDF = "%PDF-1.4 taco stand".getBytes(StandardCharsets.US_ASCII);

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ExtractionService service;

	@Test
	void uploadReturnsTheDraftWithLocation() throws Exception {
		Extraction extraction = ExtractionFixtures.draftExtraction("ext_TEST");
		when(service.extract(eq("taco.pdf"), any())).thenReturn(extraction);
		when(service.draftOf(extraction)).thenReturn(ExtractionFixtures.tacoStandDraft());

		MvcResult result = upload(new MockMultipartFile("document", "taco.pdf", "application/pdf", PDF));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/v1/extractions/ext_TEST");
		JsonNode body = body(result);
		assertThat(body.get("status").asString()).isEqualTo("DRAFT");
		assertThat(body.at("/draft/businessName").asString()).isEqualTo("Coastal Taco Stand LLC");
		assertThat(body.at("/draft").has("annualRevenue")).as("missing fields are omitted, not null").isFalse();
		assertThat(body.at("/missingFields/0").asString()).isEqualTo("/annualRevenue");
		assertThat(body.at("/flags/1/reason").asString()).isEqualTo("SUSPECTED_INSTRUCTION");
	}

	@Test
	void anUnreadableDocumentIs422WithTheReason() throws Exception {
		when(service.extract(any(), any()))
			.thenThrow(new UnreadableDocumentException("The document has no text layer; it may be a scan, which needs OCR."));

		MvcResult result = upload(new MockMultipartFile("document", "scan.pdf", "application/pdf", PDF));

		JsonNode problem = assertProblem(result, 422, "unreadable-document", "Document could not be read");
		assertThat(problem.get("detail").asString()).contains("no text layer");
	}

	@Test
	void aModelFailureIs502AndHidesTheCause() throws Exception {
		when(service.extract(any(), any()))
			.thenThrow(new ExtractionFailedException("The model call failed with HTTP 429.", new RuntimeException("quota key=secret")));

		MvcResult result = upload(new MockMultipartFile("document", "taco.pdf", "application/pdf", PDF));

		JsonNode problem = assertProblem(result, 502, "extraction-failed", "Extraction failed");
		assertThat(problem.get("detail").asString()).isEqualTo("The AI model couldn't draft this document. Try again shortly.");
		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret", "HTTP 429");
	}

	@Test
	void extractionSwitchedOffIs503() throws Exception {
		when(service.extract(any(), any())).thenThrow(new ExtractionUnavailableException());

		MvcResult result = upload(new MockMultipartFile("document", "taco.pdf", "application/pdf", PDF));

		JsonNode problem = assertProblem(result, 503, "service-unavailable", "Service unavailable");
		assertThat(problem.get("detail").asString()).isEqualTo("Document extraction is not enabled on this service.");
	}

	@Test
	void anUploadOverTheLimitIs413() throws Exception {
		when(service.extract(any(), any())).thenThrow(new MaxUploadSizeExceededException(10 * 1024 * 1024));

		MvcResult result = upload(new MockMultipartFile("document", "huge.pdf", "application/pdf", PDF));

		JsonNode problem = assertProblem(result, 413, "document-too-large", "Document too large");
		assertThat(problem.get("detail").asString()).isEqualTo("Documents can be at most 10 MB.");
	}

	@Test
	void anUploadWithoutTheDocumentPartIs400() throws Exception {
		MvcResult result = upload(new MockMultipartFile("file", "taco.pdf", "application/pdf", PDF));

		assertProblem(result, 400, "validation-error", "Request failed schema validation");
		verifyNoInteractions(service);
	}

	@Test
	void jsonInsteadOfAnUploadIs415() throws Exception {
		MvcResult result = perform(post(EXTRACTIONS).contentType(MediaType.APPLICATION_JSON).content("{}"), "POST",
				EXTRACTIONS);

		assertProblem(result, 415, "unsupported-media-type", "Unsupported media type");
	}

	@Test
	void anUnknownExtractionIs404() throws Exception {
		when(service.get("ext_NOPE")).thenThrow(new ExtractionNotFoundException("ext_NOPE"));

		MvcResult result = perform(get(EXTRACTION, "ext_NOPE"), "GET", EXTRACTION);

		assertProblem(result, 404, "not-found", "Resource not found");
	}

	@Test
	void aConfirmedExtractionShowsItsSubmission() throws Exception {
		Extraction extraction = ExtractionFixtures.draftExtraction("ext_TEST");
		extraction.confirm("sub_TEST", ExtractionFixtures.CREATED_AT.plusSeconds(300));
		when(service.get("ext_TEST")).thenReturn(extraction);
		when(service.draftOf(extraction)).thenReturn(ExtractionFixtures.tacoStandDraft());

		MvcResult result = perform(get(EXTRACTION, "ext_TEST"), "GET", EXTRACTION);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(body(result).get("submissionId").asString()).isEqualTo("sub_TEST");
	}

	@Test
	void confirmingCreatesTheSubmissionWithItsLocation() throws Exception {
		when(service.confirm(eq("ext_TEST"), any(), any())).thenReturn(ExtractionFixtures.submission("sub_TEST"));

		MvcResult result = confirm("ext_TEST", reviewedRequest());

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/v1/submissions/sub_TEST");
		assertThat(body(result).get("id").asString()).isEqualTo("sub_TEST");
	}

	@Test
	void confirmingTwiceIs409() throws Exception {
		when(service.confirm(eq("ext_TEST"), any(), any()))
			.thenThrow(new ExtractionAlreadyConfirmedException("ext_TEST", "sub_TEST"));

		MvcResult result = confirm("ext_TEST", reviewedRequest());

		JsonNode problem = assertProblem(result, 409, "extraction-already-confirmed", "Extraction already confirmed");
		assertThat(problem.get("detail").asString())
			.isEqualTo("Extraction ext_TEST was already confirmed as submission sub_TEST.");
	}

	@Test
	void anIncompleteConfirmationIsAValidationErrorAndReachesNoService() throws Exception {
		ObjectNode request = reviewedRequest();
		request.remove("annualRevenue");

		MvcResult result = confirm("ext_TEST", request);

		assertProblem(result, 400, "validation-error", "Request failed schema validation");
		verifyNoInteractions(service);
	}

	private MvcResult upload(MockMultipartFile file) throws Exception {
		return perform(multipart(EXTRACTIONS).file(file), "POST", EXTRACTIONS);
	}

	private MvcResult confirm(String id, ObjectNode request) throws Exception {
		return perform(post(CONFIRM, id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)),
				"POST", CONFIRM);
	}

	private MvcResult perform(RequestBuilder request, String method, String pathTemplate)
			throws Exception {
		MvcResult result = mvc.perform(request).andReturn();
		contract.assertResponseMatches(method, pathTemplate, result);
		return result;
	}

	private static ObjectNode reviewedRequest() {
		ObjectNode request = contract.example("SubmissionRequestRestaurant");
		request.put("effectiveDate", LocalDate.now(ZoneOffset.UTC).plusDays(7).toString());
		return request;
	}

	private static JsonNode assertProblem(MvcResult result, int status, String typeSlug, String title)
			throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(status);
		assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		JsonNode problem = body(result);
		assertThat(problem.get("type").asString()).isEqualTo(PROBLEM_BASE + typeSlug);
		assertThat(problem.get("title").asString()).isEqualTo(title);
		assertThat(problem.get("status").asInt()).isEqualTo(status);
		assertThat(problem.get("instance").asString()).startsWith("/v1/");
		assertThat(problem.get("traceId").asString()).matches("^[0-9a-f]{32}$");
		return problem;
	}

	private static JsonNode body(MvcResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

}
