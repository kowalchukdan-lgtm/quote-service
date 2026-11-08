package com.kowalchuk.quoteservice.extraction;

import java.time.Duration;

/**
 * A draft plus what it cost: the model version and tokens (cost) and the time taken (latency),
 * so both can be tracked per extraction. The model's raw answer and the prompt version are kept
 * for the intake archive; the document text is not.
 */
public record ExtractionResult(ExtractedSubmission draft, String model, int inputTokens, int outputTokens,
		Duration elapsed, String rawResponse, String promptVersion) {
}
