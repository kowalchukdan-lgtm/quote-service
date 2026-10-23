package com.kowalchuk.quoteservice.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.kowalchuk.quoteservice.OpenApiContract;
import com.kowalchuk.quoteservice.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end tests against a real Postgres. Every response is also checked against
 * {@code quote-api.yaml}: status and content type must be declared for the operation, and
 * the body must match the declared schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SubmissionApiIT {

	private static final String SUBMISSIONS = "/v1/submissions";

	private static final String SUBMISSION = "/v1/submissions/{submissionId}";

	private static final String PROBLEM_BASE = "https://api.example.com/problems/";

	private static final OpenApiContract contract = OpenApiContract.load();

	private static final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;

	@Test
	void createsSubmissionAndReadsItBack() throws Exception {
		MvcResult created = create(validRequest());

		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
		assertThat(location).matches("^/v1/submissions/sub_[0-9A-HJKMNP-TV-Z]{26}$");
		JsonNode body = body(created);
		assertThat("/v1/submissions/" + body.get("id").asString()).isEqualTo(location);
		assertThat(body.get("businessName").asString()).isEqualTo("Lone Star Smokehouse LLC");
		assertThat(body.at("/classOfBusiness/description").asString()).isEqualTo("Full-Service Restaurants");
		assertThat(body.at("/annualRevenue/amount").asString()).isEqualTo("1200000.00");
		assertThat(body.at("/address").has("line2")).as("absent optional fields are omitted, not null").isFalse();
		assertThat(body.get("createdAt").asString()).endsWith("Z");

		MvcResult fetched = perform(get(location), "GET", SUBMISSION);
		assertThat(fetched.getResponse().getStatus()).isEqualTo(200);
		assertThat(body(fetched)).as("GET returns exactly what POST returned").isEqualTo(body);
	}

	@Test
	void missingRequiredPropertyIsAValidationError() throws Exception {
		MvcResult result = create(validRequest(request -> ((ObjectNode) request.get("address")).remove("postalCode")));

		JsonNode problem = assertProblem(result, 400, "validation-error", "Request failed schema validation");
		assertThat(problem.get("instance").asString()).isEqualTo(SUBMISSIONS);
		assertThat(problem.get("detail").asString()).isEqualTo("The request body has 1 validation error.");
		assertErrors(problem, "/address/postalCode", "Required property is missing.");
	}

	@Test
	void everyBeanValidationErrorIsReportedTogether() throws Exception {
		MvcResult result = create(validRequest(request -> {
			ObjectNode address = (ObjectNode) request.get("address");
			address.remove("postalCode");
			address.put("region", "Texas");
		}));

		JsonNode problem = assertProblem(result, 400, "validation-error", "Request failed schema validation");
		assertThat(problem.get("detail").asString()).isEqualTo("The request body has 2 validation errors.");
		assertThat(pointers(problem)).containsExactly("/address/postalCode", "/address/region");
	}

	@Test
	void unknownPropertyIsAValidationError() throws Exception {
		MvcResult result = create(validRequest(request -> request.put("efectiveDate", "2026-11-01")));

		assertErrors(assertProblem(result, 400, "validation-error", "Request failed schema validation"),
				"/efectiveDate", "Unknown property.");
	}

	@Test
	void numericAmountIsRejectedBecauseMoneyIsAString() throws Exception {
		MvcResult result = create(
				validRequest(request -> ((ObjectNode) request.get("annualRevenue")).put("amount", 1200000.10)));

		assertErrors(assertProblem(result, 400, "validation-error", "Request failed schema validation"),
				"/annualRevenue/amount", "Must be a string.");
	}

	@Test
	void impossibleDateIsAValidationError() throws Exception {
		MvcResult result = create(validRequest(request -> request.put("effectiveDate", "2026-02-30")));

		assertErrors(assertProblem(result, 400, "validation-error", "Request failed schema validation"),
				"/effectiveDate", "Must be a full-date (YYYY-MM-DD).");
	}

	@Test
	void integerDateIsNotLenientlyAccepted() throws Exception {
		MvcResult result = create(validRequest(request -> request.put("effectiveDate", 20000)));

		assertErrors(assertProblem(result, 400, "validation-error", "Request failed schema validation"),
				"/effectiveDate", "Must be a full-date (YYYY-MM-DD).");
	}

	@Test
	void invalidJsonIsAMalformedRequest() throws Exception {
		MvcResult result = perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content("{\"business"),
				"POST", SUBMISSIONS);

		JsonNode problem = assertProblem(result, 400, "malformed-request", "Malformed request body");
		assertThat(problem.get("detail").asString()).isEqualTo("The request body is not valid JSON.");
	}

	@Test
	void missingBodyIsAMalformedRequest() throws Exception {
		MvcResult result = perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON), "POST", SUBMISSIONS);

		JsonNode problem = assertProblem(result, 400, "malformed-request", "Malformed request body");
		assertThat(problem.get("detail").asString()).isEqualTo("The request body is missing.");
	}

	@Test
	void nonJsonContentTypeIsUnsupported() throws Exception {
		MvcResult result = perform(post(SUBMISSIONS).contentType(MediaType.TEXT_PLAIN).content("hello"), "POST",
				SUBMISSIONS);

		assertProblem(result, 415, "unsupported-media-type", "Unsupported media type");
	}

	@Test
	void nonJsonAcceptIsNotAcceptable() throws Exception {
		MvcResult result = perform(get(SUBMISSION, "sub_X").accept(MediaType.APPLICATION_XML), "GET", SUBMISSION);

		assertProblem(result, 406, "not-acceptable", "Not acceptable");
	}

	@Test
	void businessRuleViolationsAreReportedTogetherAs422() throws Exception {
		MvcResult result = create(validRequest(request -> {
			request.put("effectiveDate", today().minusDays(1).toString());
			((ObjectNode) request.get("classOfBusiness")).put("naicsCode", "213111");
			((ObjectNode) request.get("annualRevenue")).put("currency", "EUR");
		}));

		JsonNode problem = assertProblem(result, 422, "business-rule-violation",
				"Submission violates a business rule");
		assertThat(pointers(problem)).containsExactly("/annualRevenue/currency", "/classOfBusiness/naicsCode",
				"/effectiveDate");
	}

	@Test
	void unknownSubmissionIsNotFound() throws Exception {
		MvcResult result = perform(get(SUBMISSION, "sub_DOESNOTEXIST"), "GET", SUBMISSION);

		JsonNode problem = assertProblem(result, 404, "not-found", "Resource not found");
		assertThat(problem.get("detail").asString()).isEqualTo("No submission with ID 'sub_DOESNOTEXIST' was found.");
		assertThat(problem.get("instance").asString()).isEqualTo("/v1/submissions/sub_DOESNOTEXIST");
	}

	@Test
	void oversizeSubmissionIdIsNotFoundBecauseGetDefinesNo400() throws Exception {
		MvcResult result = perform(get(SUBMISSION, "s".repeat(65)), "GET", SUBMISSION);

		assertProblem(result, 404, "not-found", "Resource not found");
	}

	@Test
	void unimplementedQuoteEndpointIsNotFound() throws Exception {
		MvcResult result = perform(get("/v1/quotes/{quoteId}", "qte_X"), "GET", "/v1/quotes/{quoteId}");

		assertProblem(result, 404, "not-found", "Resource not found");
	}

	@Test
	void overlongIdempotencyKeyIsAValidationError() throws Exception {
		MvcResult result = create(validRequest(), "k".repeat(256));

		JsonNode problem = assertProblem(result, 400, "validation-error", "Request failed schema validation");
		assertThat(problem.get("detail").asString()).startsWith("Header Idempotency-Key: ");
		assertThat(problem.has("errors")).as("header errors have no body pointer").isFalse();
	}

	@Test
	void incomingTraceparentTraceIdIsUsed() throws Exception {
		MvcResult result = perform(get(SUBMISSION, "sub_DOESNOTEXIST").header("traceparent",
				"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"), "GET", SUBMISSION);

		assertThat(body(result).get("traceId").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
	}

	/**
	 * Characterisation test for a known gap. JSON Schema regexes follow ECMAScript, where
	 * {@code \s} includes U+00A0, so the contract rejects this name; Java's {@code \s} is
	 * ASCII-only, so the server accepts it. If a blank-name check is added, expect 400 here.
	 */
	@Test
	void nameOfOnlyNonBreakingSpacesIsAcceptedToday() throws Exception {
		MvcResult result = create(validRequest(request -> request.put("businessName", "  ")));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
	}

	/**
	 * Characterisation test for deferred idempotency. The contract promises that the same key
	 * and body replay the original response; today each request creates a new submission.
	 * When dedupe ships, expect the same ID twice.
	 */
	@Test
	void sameIdempotencyKeyCreatesADuplicateToday() throws Exception {
		String key = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
		ObjectNode request = validRequest();

		MvcResult first = create(request, key);
		MvcResult second = create(request, key);

		assertThat(first.getResponse().getStatus()).isEqualTo(201);
		assertThat(second.getResponse().getStatus()).isEqualTo(201);
		assertThat(body(second).get("id").asString()).isNotEqualTo(body(first).get("id").asString());
	}

	private MvcResult create(ObjectNode request) throws Exception {
		return create(request, null);
	}

	private MvcResult create(ObjectNode request, String idempotencyKey) throws Exception {
		MockHttpServletRequestBuilder builder = post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(request));
		if (idempotencyKey != null) {
			builder.header("Idempotency-Key", idempotencyKey);
		}
		return perform(builder, "POST", SUBMISSIONS);
	}

	private MvcResult perform(MockHttpServletRequestBuilder request, String method, String pathTemplate)
			throws Exception {
		MvcResult result = mvc.perform(request).andReturn();
		contract.assertResponseMatches(method, pathTemplate, result);
		return result;
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

	private static void assertErrors(JsonNode problem, String pointer, String detail) {
		assertThat(problem.get("errors")).hasSize(1);
		assertThat(problem.at("/errors/0/pointer").asString()).isEqualTo(pointer);
		assertThat(problem.at("/errors/0/detail").asString()).isEqualTo(detail);
	}

	private static List<String> pointers(JsonNode problem) {
		return problem.get("errors").valueStream().map(error -> error.get("pointer").asString()).toList();
	}

	private static JsonNode body(MvcResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	/** The contract's restaurant example, with an effective date that is always valid. */
	private static ObjectNode validRequest() {
		ObjectNode request = contract.example("SubmissionRequestRestaurant");
		request.put("effectiveDate", today().plusDays(7).toString());
		return request;
	}

	private static ObjectNode validRequest(Consumer<ObjectNode> change) {
		ObjectNode request = validRequest();
		change.accept(request);
		return request;
	}

	private static LocalDate today() {
		return LocalDate.now(ZoneOffset.UTC);
	}

}
