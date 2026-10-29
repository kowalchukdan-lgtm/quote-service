package com.kowalchuk.quoteservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.kowalchuk.quoteservice.OpenApiContract;
import com.kowalchuk.quoteservice.config.ClockConfig;
import com.kowalchuk.quoteservice.config.JacksonConfig;
import com.kowalchuk.quoteservice.quote.Quote;
import com.kowalchuk.quoteservice.quote.QuoteController;
import com.kowalchuk.quoteservice.quote.QuoteMapper;
import com.kowalchuk.quoteservice.quote.QuoteNotFoundException;
import com.kowalchuk.quoteservice.quote.QuoteService;
import com.kowalchuk.quoteservice.quote.RatingInputs;
import com.kowalchuk.quoteservice.rating.RatingResult.AppliedFactor;
import com.kowalchuk.quoteservice.rating.RatingResult.Quoted;
import com.kowalchuk.quoteservice.submission.SubmissionController;
import com.kowalchuk.quoteservice.submission.SubmissionMapper;
import com.kowalchuk.quoteservice.submission.SubmissionNotFoundException;
import com.kowalchuk.quoteservice.submission.SubmissionService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The web layer alone: controllers, {@link ProblemHandler}, {@link TraceIdFilter} and JSON,
 * with the services mocked. Forcing service outcomes here is cheap, so this covers status codes
 * and Problem Details for cases that are hard to produce end to end (an unexpected exception, a
 * quote-time 422). Every response is also checked against the contract.
 */
@Tag("slice")
@WebMvcTest(controllers = { QuoteController.class, SubmissionController.class })
@Import({ QuoteMapper.class, SubmissionMapper.class, ClockConfig.class, JacksonConfig.class })
class ApiWebLayerTest {

	private static final String SUBMISSIONS = "/v1/submissions";

	private static final String QUOTES = "/v1/submissions/{submissionId}/quotes";

	private static final String QUOTE = "/v1/quotes/{quoteId}";

	private static final String PROBLEM_BASE = "https://api.example.com/problems/";

	private static final OpenApiContract contract = OpenApiContract.load();

	private static final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;

	@MockitoBean
	QuoteService quoteService;

	@MockitoBean
	SubmissionService submissionService;

	@Test
	void createdQuoteIs201WithLocationAndNoStore() throws Exception {
		when(quoteService.create("sub_TEST")).thenReturn(quotedQuote());

		MvcResult result = perform(post(QUOTES, "sub_TEST"), "POST", QUOTES);

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/v1/quotes/qte_TEST");
		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
		JsonNode body = body(result);
		assertThat(body.get("status").asString()).isEqualTo("QUOTED");
		assertThat(body.at("/premium/amount").asString()).isEqualTo("2520.00");
	}

	@Test
	void fetchedQuoteIs200WithNoStore() throws Exception {
		when(quoteService.get("qte_TEST")).thenReturn(quotedQuote());

		MvcResult result = perform(get(QUOTE, "qte_TEST"), "GET", QUOTE);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
	}

	@Test
	void unknownQuoteIsAProblemDetails404() throws Exception {
		when(quoteService.get("qte_NOPE")).thenThrow(new QuoteNotFoundException("qte_NOPE"));

		JsonNode problem = assertProblem(perform(get(QUOTE, "qte_NOPE"), "GET", QUOTE), 404, "not-found",
				"Resource not found");

		assertThat(problem.get("detail").asString()).isEqualTo("No quote with ID 'qte_NOPE' was found.");
		assertThat(problem.get("instance").asString()).isEqualTo("/v1/quotes/qte_NOPE");
	}

	@Test
	void quoteForUnknownSubmissionIs404() throws Exception {
		when(quoteService.create("sub_NOPE")).thenThrow(new SubmissionNotFoundException("sub_NOPE"));

		JsonNode problem = assertProblem(perform(post(QUOTES, "sub_NOPE"), "POST", QUOTES), 404, "not-found",
				"Resource not found");

		assertThat(problem.get("detail").asString()).isEqualTo("No submission with ID 'sub_NOPE' was found.");
	}

	@Test
	void quoteTimeRuleViolationIs422WithAWholeRequestPointer() throws Exception {
		when(quoteService.create("sub_PAST")).thenThrow(new BusinessRuleViolationException(
				List.of(new FieldViolation("", "Effective date 2026-10-22 of submission sub_PAST is in the past."))));

		JsonNode problem = assertProblem(perform(post(QUOTES, "sub_PAST"), "POST", QUOTES), 422,
				"business-rule-violation", "Submission violates a business rule");

		assertThat(problem.at("/errors/0/pointer").asString()).isEmpty();
		assertThat(problem.at("/errors/0/detail").asString()).contains("is in the past");
	}

	@Test
	void submissionRuleViolationsAre422SortedByPointer() throws Exception {
		when(submissionService.create(any())).thenThrow(new BusinessRuleViolationException(
				List.of(new FieldViolation("/effectiveDate", "Effective date is in the past."),
						new FieldViolation("/address/country", "Country CA is not accepted."))));

		JsonNode problem = assertProblem(createSubmission(validSubmission()), 422, "business-rule-violation",
				"Submission violates a business rule");

		assertThat(problem.get("errors").valueStream().map(error -> error.get("pointer").asString()).toList())
			.containsExactly("/address/country", "/effectiveDate");
	}

	@Test
	void missingRequiredFieldIs400AndNeverReachesTheService() throws Exception {
		ObjectNode request = validSubmission();
		((ObjectNode) request.get("address")).remove("postalCode");

		JsonNode problem = assertProblem(createSubmission(request), 400, "validation-error",
				"Request failed schema validation");

		assertThat(problem.at("/errors/0/pointer").asString()).isEqualTo("/address/postalCode");
		assertThat(problem.at("/errors/0/detail").asString()).isEqualTo("Required property is missing.");
		verifyNoInteractions(submissionService);
	}

	@Test
	void malformedJsonIs400MalformedRequest() throws Exception {
		MvcResult result = perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content("{"), "POST",
				SUBMISSIONS);

		assertProblem(result, 400, "malformed-request", "Malformed request body");
		verifyNoInteractions(submissionService);
	}

	@Test
	void nonJsonBodyIs415() throws Exception {
		MvcResult result = perform(post(SUBMISSIONS).contentType(MediaType.TEXT_PLAIN).content("hello"), "POST",
				SUBMISSIONS);

		assertProblem(result, 415, "unsupported-media-type", "Unsupported media type");
	}

	@Test
	void nonJsonAcceptIs406() throws Exception {
		MvcResult result = perform(get(QUOTE, "qte_TEST").accept(MediaType.TEXT_PLAIN), "GET", QUOTE);

		assertProblem(result, 406, "not-acceptable", "Not acceptable");
		verifyNoInteractions(quoteService);
	}

	@Test
	void overlongIdIs404BecauseItCannotExist() throws Exception {
		MvcResult result = perform(get(QUOTE, "q".repeat(65)), "GET", QUOTE);

		assertProblem(result, 404, "not-found", "Resource not found");
		verifyNoInteractions(quoteService);
	}

	@Test
	void unexpectedExceptionIsAGeneric500ThatLeaksNothing() throws Exception {
		when(quoteService.get("qte_BOOM")).thenThrow(new IllegalStateException("db password=hunter2"));

		MvcResult result = perform(get(QUOTE, "qte_BOOM"), "GET", QUOTE);

		JsonNode problem = assertProblem(result, 500, "internal-error", "Internal server error");
		assertThat(problem.get("detail").asString())
			.isEqualTo("An unexpected error occurred. Quote the traceId when contacting support.");
		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("hunter2");
	}

	@Test
	void incomingTraceparentBecomesTheProblemTraceId() throws Exception {
		when(quoteService.get("qte_NOPE")).thenThrow(new QuoteNotFoundException("qte_NOPE"));

		MvcResult result = perform(get(QUOTE, "qte_NOPE").header("traceparent",
				"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"), "GET", QUOTE);

		assertThat(body(result).get("traceId").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
	}

	private MvcResult createSubmission(ObjectNode request) throws Exception {
		return perform(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(request)), "POST", SUBMISSIONS);
	}

	private MvcResult perform(MockHttpServletRequestBuilder request, String method, String pathTemplate)
			throws Exception {
		MvcResult result = mvc.perform(request).andReturn();
		contract.assertResponseMatches(method, pathTemplate, result);
		return result;
	}

	private static ObjectNode validSubmission() {
		ObjectNode request = contract.example("SubmissionRequestRestaurant");
		request.put("effectiveDate", LocalDate.now(ZoneOffset.UTC).plusDays(7).toString());
		return request;
	}

	private static Quote quotedQuote() {
		Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		RatingInputs inputs = new RatingInputs(LocalDate.now(ZoneOffset.UTC).plusDays(7), "722511", "TX",
				new BigDecimal("1000000.00"), new BigDecimal("2000000.00"), new BigDecimal("1200000.00"));
		Quoted quoted = new Quoted(new BigDecimal("2520.00"), new BigDecimal("500.00"), false,
				List.of(new AppliedFactor("CLASS", "Class of business", "722511", new BigDecimal("2.1000")),
						new AppliedFactor("LIMIT", "Limit combination", "1000000/2000000", new BigDecimal("1.0000"))));
		return Quote.create("qte_TEST", "sub_TEST", inputs, new Quote.RateRowIds(1L, 2L, 3L), quoted, "USD",
				createdAt, createdAt.plus(Duration.ofDays(30)));
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
