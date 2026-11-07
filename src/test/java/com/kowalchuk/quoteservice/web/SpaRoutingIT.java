package com.kowalchuk.quoteservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.kowalchuk.quoteservice.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Angular app served from the jar: deep links forward to {@code index.html}, caching suits
 * each file, and everything outside the app's routes keeps the API's 404 Problem Details.
 * <p>
 * {@code src/test/resources/static/} holds a stand-in {@code index.html} and hashed bundle; the
 * real app is copied into the jar by the Docker build.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SpaRoutingIT {

	private static final String PROBLEM_JSON = "application/problem+json";

	private static final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;

	@ParameterizedTest
	@ValueSource(strings = { "/extractions/ext_01JB7Q6M2H3J4K5L6M7N8P9Q0R", "/submissions/sub_01JB7Q8N4K2M3P5R6S7T8V9W0X" })
	void appDeepLinksForwardToIndexHtml(String path) throws Exception {
		MvcResult result = mvc.perform(get(path)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getForwardedUrl()).isEqualTo("/index.html");
	}

	@Test
	void rootServesTheAppThroughTheWelcomePage() throws Exception {
		MvcResult result = mvc.perform(get("/")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getForwardedUrl()).isEqualTo("index.html");
	}

	@Test
	void onlyGetIsForwarded() throws Exception {
		MvcResult result = mvc.perform(post("/submissions/sub_1")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(405);
		assertThat(result.getResponse().getForwardedUrl()).isNull();
	}

	@Test
	void indexHtmlIsNeverStored() throws Exception {
		MvcResult result = mvc.perform(get("/index.html")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentType()).startsWith("text/html");
		assertThat(result.getResponse().getContentAsString()).contains("<app-root>");
		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
	}

	@Test
	void hashedBundlesAreCachedForAYear() throws Exception {
		MvcResult result = mvc.perform(get("/main-TEST1234.js")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
			.isEqualTo("max-age=31536000, public, immutable");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/v1/nope", "/v1/submissions/sub_1/nope", "/actuator/nope", "/extractions",
			"/submissions/sub_1/extra", "/main-MISSING.js" })
	void pathsOutsideTheAppAreStillProblemDetails404(String path) throws Exception {
		MvcResult result = mvc.perform(get(path)).andReturn();

		assertNotFoundProblem(result);
		assertThat(result.getResponse().getForwardedUrl()).isNull();
	}

	@Test
	void anUnknownApiResourceIsTheApis404NotTheApp() throws Exception {
		MvcResult result = mvc.perform(get("/v1/extractions/{id}", "ext_DOESNOTEXIST")).andReturn();

		JsonNode problem = assertNotFoundProblem(result);
		assertThat(problem.get("instance").asString()).isEqualTo("/v1/extractions/ext_DOESNOTEXIST");
	}

	@Test
	void healthIsStillServed() throws Exception {
		assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
	}

	private static JsonNode assertNotFoundProblem(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(result.getResponse().getContentType()).startsWith(PROBLEM_JSON);
		JsonNode problem = json.readTree(result.getResponse().getContentAsString());
		assertThat(problem.get("type").asString()).isEqualTo("https://api.example.com/problems/not-found");
		assertThat(problem.get("title").asString()).isEqualTo("Resource not found");
		return problem;
	}

}
