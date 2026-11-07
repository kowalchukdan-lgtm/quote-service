package com.kowalchuk.quoteservice.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import com.kowalchuk.quoteservice.OpenApiContract;
import com.kowalchuk.quoteservice.TestcontainersConfiguration;
import com.kowalchuk.quoteservice.extraction.GoldenSet;
import com.kowalchuk.quoteservice.extraction.GoldenSetScorer;
import com.kowalchuk.quoteservice.extraction.ModelClient;
import com.kowalchuk.quoteservice.extraction.ModelResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Document to quote-ready submission, end to end against a real Postgres: upload, review,
 * confirm. Extraction is switched on with a dummy endpoint and the model is replaced by one that
 * answers the golden set perfectly, so this tests the service, not the model.
 */
@SpringBootTest(properties = "quote.extraction.endpoint=https://ai-quote-service.test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ExtractionApiIT {

	private static final String EXTRACTIONS = "/v1/extractions";

	private static final String EXTRACTION = "/v1/extractions/{extractionId}";

	private static final String CONFIRM = "/v1/extractions/{extractionId}/confirm";

	private static final String SUBMISSION = "/v1/submissions/{submissionId}";

	private static final OpenApiContract contract = OpenApiContract.load();

	private static final JsonMapper json = JsonMapper.builder().build();

	private static final GoldenSet.Case TACO_STAND = GoldenSet.load("sample-04-coastal-taco-stand");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ModelClient model;

	@BeforeEach
	void modelAnswersTheTacoStandPerfectly() {
		when(model.complete(any())).thenReturn(new ModelResponse(GoldenSetScorer.perfectModelAnswer(TACO_STAND),
				"gpt-5-mini-2025-08-07", 1304, 929));
	}

	@Test
	void uploadReviewAndConfirmADocument() throws Exception {
		MvcResult uploaded = upload(TACO_STAND.pdf());

		assertThat(uploaded.getResponse().getStatus()).isEqualTo(201);
		String location = uploaded.getResponse().getHeader(HttpHeaders.LOCATION);
		assertThat(location).matches("^/v1/extractions/ext_[0-9A-HJKMNP-TV-Z]{26}$");
		JsonNode extraction = body(uploaded);
		assertThat(extraction.get("status").asString()).isEqualTo("DRAFT");
		assertThat(extraction.at("/document/fileName").asString()).isEqualTo("coastal-taco-stand.pdf");
		assertThat(extraction.at("/document/sha256").asString()).hasSize(64);
		assertThat(extraction.at("/draft/businessName").asString()).isEqualTo("Coastal Taco Stand LLC");
		assertThat(extraction.at("/missingFields/0").asString()).isEqualTo("/annualRevenue");

		MvcResult fetched = perform(get(location), "GET", EXTRACTION);
		assertThat(body(fetched)).as("GET returns what POST returned").isEqualTo(extraction);

		String id = extraction.get("id").asString();
		MvcResult confirmed = confirm(id, reviewed(extraction, "722513"));

		assertThat(confirmed.getResponse().getStatus()).isEqualTo(201);
		String submissionLocation = confirmed.getResponse().getHeader(HttpHeaders.LOCATION);
		assertThat(submissionLocation).startsWith("/v1/submissions/sub_");
		JsonNode submission = body(confirmed);
		assertThat(submission.at("/annualRevenue/amount").asString()).isEqualTo("650000.00");
		assertThat(submission.at("/classOfBusiness/description").asString()).isEqualTo("Limited-Service Restaurants");
		assertThat(body(perform(get(submissionLocation), "GET", SUBMISSION))).isEqualTo(submission);

		JsonNode afterConfirm = body(perform(get(location), "GET", EXTRACTION));
		assertThat(afterConfirm.get("status").asString()).isEqualTo("CONFIRMED");
		assertThat(afterConfirm.get("submissionId").asString()).isEqualTo(submission.get("id").asString());

		MvcResult again = confirm(id, reviewed(extraction, "722513"));
		assertThat(again.getResponse().getStatus()).isEqualTo(409);
	}

	@Test
	void aConfirmationThatBreaksARuleCreatesNothingAndCanBeCorrected() throws Exception {
		JsonNode extraction = body(upload(TACO_STAND.pdf()));
		String id = extraction.get("id").asString();

		MvcResult rejected = confirm(id, reviewed(extraction, "213111"));

		assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
		assertThat(body(rejected).at("/errors/0/pointer").asString()).isEqualTo("/classOfBusiness/naicsCode");
		assertThat(body(perform(get(EXTRACTION, id), "GET", EXTRACTION)).get("status").asString())
			.as("the draft is untouched").isEqualTo("DRAFT");

		MvcResult corrected = confirm(id, reviewed(extraction, "722513"));
		assertThat(corrected.getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void anUnreadableDocumentNeverReachesTheModel() throws Exception {
		MvcResult result = upload("this is not a pdf".getBytes(StandardCharsets.US_ASCII));

		assertThat(result.getResponse().getStatus()).isEqualTo(422);
		assertThat(body(result).get("detail").asString()).isEqualTo("The document is not a readable PDF.");
		verify(model, never()).complete(any());
	}

	@Test
	void anUnknownExtractionIs404() throws Exception {
		MvcResult result = perform(get(EXTRACTION, "ext_DOESNOTEXIST"), "GET", EXTRACTION);

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
	}

	/**
	 * What a reviewer sends: the draft, with the missing revenue filled in, a current effective
	 * date (the sample's fixed date would eventually be in the past) and the chosen class.
	 */
	private static ObjectNode reviewed(JsonNode extraction, String naicsCode) {
		ObjectNode request = (ObjectNode) extraction.get("draft").deepCopy();
		request.putObject("annualRevenue").put("amount", "650000.00").put("currency", "USD");
		request.put("effectiveDate", LocalDate.now(ZoneOffset.UTC).plusDays(7).toString());
		((ObjectNode) request.get("classOfBusiness")).put("naicsCode", naicsCode);
		return request;
	}

	private MvcResult upload(byte[] content) throws Exception {
		return perform(multipart(EXTRACTIONS)
			.file(new MockMultipartFile("document", "coastal-taco-stand.pdf", "application/pdf", content)), "POST",
				EXTRACTIONS);
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

	private static JsonNode body(MvcResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

}
