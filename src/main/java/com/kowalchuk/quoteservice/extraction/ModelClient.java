package com.kowalchuk.quoteservice.extraction;

/**
 * The one thing extraction needs from a language model: a JSON answer that follows a schema.
 * The Azure OpenAI adapter lives in {@code extraction.azure}; tests use a fake. Keeping this an
 * interface means the model or provider can change without touching the extraction logic.
 */
public interface ModelClient {

	/**
	 * @throws ExtractionFailedException if the call fails or the model doesn't return usable JSON
	 */
	ModelResponse complete(ModelRequest request);

}
