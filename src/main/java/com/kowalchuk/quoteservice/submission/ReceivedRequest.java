package com.kowalchuk.quoteservice.submission;

import org.jspecify.annotations.Nullable;

/**
 * A submission request as it arrived, for the intake archive.
 * @param json the body exactly as received
 * @param extractionId the draft being confirmed, when the request came through an extraction
 */
public record ReceivedRequest(String json, @Nullable String extractionId) {
}
