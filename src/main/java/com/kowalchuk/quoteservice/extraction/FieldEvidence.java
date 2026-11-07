package com.kowalchuk.quoteservice.extraction;

/**
 * Where a value came from: a short quote from the document, so the reviewer can check it
 * without hunting through the original.
 */
public record FieldEvidence(String field, String sourceText, Confidence confidence) {
}
