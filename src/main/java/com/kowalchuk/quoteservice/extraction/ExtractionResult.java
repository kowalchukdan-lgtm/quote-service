package com.kowalchuk.quoteservice.extraction;

import java.time.Duration;

/**
 * A draft plus what it cost: the model version and tokens (cost) and the time taken (latency),
 * so both can be tracked per extraction.
 */
public record ExtractionResult(ExtractedSubmission draft, String model, int inputTokens, int outputTokens,
		Duration elapsed) {
}
