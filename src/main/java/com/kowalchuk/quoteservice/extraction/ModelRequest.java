package com.kowalchuk.quoteservice.extraction;

/**
 * A structured-output request: instructions, the user content, and the JSON Schema the answer
 * must follow (sent in strict mode, so the shape is guaranteed even if the values are wrong).
 */
public record ModelRequest(String systemPrompt, String userContent, String schemaName, String jsonSchema) {
}
