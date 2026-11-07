package com.kowalchuk.quoteservice.extraction.azure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kowalchuk.quoteservice.extraction.ExtractionFailedException;
import com.kowalchuk.quoteservice.extraction.ModelClient;
import com.kowalchuk.quoteservice.extraction.ModelRequest;
import com.kowalchuk.quoteservice.extraction.ModelResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * Calls an Azure OpenAI chat model deployment with structured outputs in strict mode, signed in
 * with an Entra ID token. Uses the v1 API ({@code /openai/v1/chat/completions}), which takes the
 * deployment name as {@code model} and needs no api-version.
 * <p>
 * Document text and model answers are never logged: they can hold policyholder data.
 */
public class AzureOpenAiModelClient implements ModelClient {

	static final String CHAT_COMPLETIONS_PATH = "/openai/v1/chat/completions";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RestClient restClient;

	private final AccessTokenProvider tokens;

	private final String deployment;

	private final @Nullable String reasoningEffort;

	/**
	 * @param restClient a client whose base URL is the AI resource endpoint
	 * @param reasoningEffort for reasoning models such as gpt-5-mini ("minimal", "low", "medium" or
	 * "high"); null or blank leaves the model's default
	 */
	public AzureOpenAiModelClient(RestClient restClient, AccessTokenProvider tokens, String deployment,
			@Nullable String reasoningEffort) {
		this.restClient = restClient;
		this.tokens = tokens;
		this.deployment = deployment;
		this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort;
	}

	@Override
	public ModelResponse complete(ModelRequest request) {
		ChatResponse response;
		try {
			response = restClient.post()
				.uri(CHAT_COMPLETIONS_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.body(body(request))
				.retrieve()
				.body(ChatResponse.class);
		}
		catch (RestClientResponseException ex) {
			throw new ExtractionFailedException("The model call failed with HTTP " + ex.getStatusCode().value() + ".",
					ex);
		}
		catch (RestClientException ex) {
			throw new ExtractionFailedException("The model call failed: " + ex.getMessage(), ex);
		}
		return toModelResponse(response);
	}

	Map<String, Object> body(ModelRequest request) {
		Map<String, Object> jsonSchema = new LinkedHashMap<>();
		jsonSchema.put("name", request.schemaName());
		jsonSchema.put("strict", true);
		jsonSchema.put("schema", JSON.readTree(request.jsonSchema()));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", deployment);
		body.put("messages", List.of(Map.of("role", "system", "content", request.systemPrompt()),
				Map.of("role", "user", "content", request.userContent())));
		body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchema));
		if (reasoningEffort != null) {
			body.put("reasoning_effort", reasoningEffort);
		}
		return body;
	}

	private ModelResponse toModelResponse(@Nullable ChatResponse response) {
		if (response == null || response.choices() == null || response.choices().isEmpty()) {
			throw new ExtractionFailedException("The model returned no answer.");
		}
		Choice choice = response.choices().get(0);
		Message message = choice.message();
		if (message != null && message.refusal() != null && !message.refusal().isBlank()) {
			throw new ExtractionFailedException("The model declined to extract this document.");
		}
		if ("length".equals(choice.finishReason())) {
			throw new ExtractionFailedException("The model's answer was cut off before it finished.");
		}
		if (message == null || message.content() == null || message.content().isBlank()) {
			throw new ExtractionFailedException("The model returned an empty answer.");
		}
		Usage usage = response.usage() != null ? response.usage() : new Usage(0, 0);
		String model = response.model() != null ? response.model() : deployment;
		return new ModelResponse(message.content(), model, usage.promptTokens(), usage.completionTokens());
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ChatResponse(@Nullable String model, @Nullable List<Choice> choices, @Nullable Usage usage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Choice(@Nullable Message message, @JsonProperty("finish_reason") @Nullable String finishReason) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Message(@Nullable String content, @Nullable String refusal) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Usage(@JsonProperty("prompt_tokens") int promptTokens,
			@JsonProperty("completion_tokens") int completionTokens) {
	}

}
