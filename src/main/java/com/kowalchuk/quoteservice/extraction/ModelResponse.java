package com.kowalchuk.quoteservice.extraction;

/**
 * The model's JSON answer, with the model version and token counts that drive cost tracking.
 */
public record ModelResponse(String content, String model, int inputTokens, int outputTokens) {
}
