package com.kowalchuk.quoteservice.extraction.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kowalchuk.quoteservice.extraction.ExtractionFailedException;
import com.kowalchuk.quoteservice.extraction.ExtractionPrompt;
import com.kowalchuk.quoteservice.extraction.ModelRequest;
import com.kowalchuk.quoteservice.extraction.ModelResponse;

class AzureOpenAiModelClientTest {

	private static final String ENDPOINT = "https://ai-quote-service.test";

	private final RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private final ModelRequest request = new ExtractionPrompt().requestFor("Keystone Pipe & Heating LLC");

	private AzureOpenAiModelClient client(String reasoningEffort) {
		return new AzureOpenAiModelClient(builder.build(), () -> "test-token", "gpt-5-mini", reasoningEffort);
	}

	private static String chatResponse(String message, String finishReason) {
		return """
				{"id": "chatcmpl-1", "object": "chat.completion", "model": "gpt-5-mini-2025-08-07",
				 "choices": [{"index": 0, "finish_reason": "%s", "message": %s}],
				 "usage": {"prompt_tokens": 1200, "completion_tokens": 350, "total_tokens": 1550}}
				""".formatted(finishReason, message);
	}

	@Test
	void sendsAStrictStructuredOutputRequestSignedInWithAToken() {
		server.expect(requestTo(ENDPOINT + "/openai/v1/chat/completions"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer test-token"))
			.andExpect(jsonPath("$.model").value("gpt-5-mini"))
			.andExpect(jsonPath("$.messages[0].role").value("system"))
			.andExpect(jsonPath("$.messages[1].role").value("user"))
			.andExpect(jsonPath("$.response_format.type").value("json_schema"))
			.andExpect(jsonPath("$.response_format.json_schema.name").value("submission_extraction"))
			.andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
			.andExpect(jsonPath("$.response_format.json_schema.schema.additionalProperties").value(false))
			.andExpect(jsonPath("$.reasoning_effort").value("low"))
			.andRespond(withSuccess(chatResponse("{\"role\": \"assistant\", \"content\": \"{}\", \"refusal\": null}",
					"stop"), MediaType.APPLICATION_JSON));

		ModelResponse response = client("low").complete(request);

		server.verify();
		assertThat(response).isEqualTo(new ModelResponse("{}", "gpt-5-mini-2025-08-07", 1200, 350));
	}

	@Test
	void leavesReasoningEffortOutWhenBlank() {
		server.expect(requestTo(ENDPOINT + "/openai/v1/chat/completions"))
			.andExpect(jsonPath("$.reasoning_effort").doesNotExist())
			.andRespond(withSuccess(chatResponse("{\"content\": \"{}\"}", "stop"), MediaType.APPLICATION_JSON));

		client(" ").complete(request);

		server.verify();
	}

	@Test
	void treatsARefusalAsAFailure() {
		server.expect(requestTo(ENDPOINT + "/openai/v1/chat/completions"))
			.andRespond(withSuccess(chatResponse("{\"content\": null, \"refusal\": \"I can't help with that.\"}",
					"stop"), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client("low").complete(request)).isInstanceOf(ExtractionFailedException.class)
			.hasMessageContaining("declined");
	}

	@Test
	void treatsACutOffAnswerAsAFailure() {
		server.expect(requestTo(ENDPOINT + "/openai/v1/chat/completions"))
			.andRespond(withSuccess(chatResponse("{\"content\": \"{\\\"businessName\\\": \\\"Keys\"}", "length"),
					MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client("low").complete(request)).isInstanceOf(ExtractionFailedException.class)
			.hasMessageContaining("cut off");
	}

	@Test
	void treatsAnEmptyAnswerAsAFailure() {
		server.expect(requestTo(ENDPOINT + "/openai/v1/chat/completions"))
			.andRespond(withSuccess("{\"choices\": []}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client("low").complete(request)).isInstanceOf(ExtractionFailedException.class)
			.hasMessageContaining("no answer");
	}

	@Test
	void reportsHttpErrorsWithoutTheResponseBody() {
		server.expect(requestTo(ENDPOINT + "/openai/v1/chat/completions"))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatThrownBy(() -> client("low").complete(request)).isInstanceOf(ExtractionFailedException.class)
			.hasMessage("The model call failed with HTTP 429.");
	}

	@Test
	void reportsServerErrors() {
		server.expect(requestTo(ENDPOINT + "/openai/v1/chat/completions")).andRespond(withServerError());

		assertThatThrownBy(() -> client("low").complete(request)).isInstanceOf(ExtractionFailedException.class)
			.hasMessageContaining("HTTP 500");
	}

}
