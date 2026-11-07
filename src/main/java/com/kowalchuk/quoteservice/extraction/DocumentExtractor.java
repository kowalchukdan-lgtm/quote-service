package com.kowalchuk.quoteservice.extraction;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads a broker document and drafts a submission from it: PDF to text, text to the model,
 * the model's answer through the parser. Plain Java, like the rating engine, so it can be tested
 * without Spring or a real model.
 * <p>
 * The result is only ever a draft. Nothing here creates a submission or a quote.
 */
public class DocumentExtractor {

	private final DocumentTextReader reader;

	private final ExtractionPrompt prompt;

	private final ModelClient model;

	private final ExtractionResponseParser parser;

	private final Clock clock;

	public DocumentExtractor(DocumentTextReader reader, ExtractionPrompt prompt, ModelClient model,
			ExtractionResponseParser parser, Clock clock) {
		this.reader = reader;
		this.prompt = prompt;
		this.model = model;
		this.parser = parser;
		this.clock = clock;
	}

	/**
	 * @throws UnreadableDocumentException if the PDF has no usable text; the model isn't called
	 * @throws ExtractionFailedException if the model call fails or its answer is unusable
	 */
	public ExtractionResult extract(byte[] pdf) {
		Instant start = clock.instant();
		String text = reader.read(pdf);
		ModelResponse response = model.complete(prompt.requestFor(text));
		ExtractedSubmission draft = parser.parse(response.content());
		return new ExtractionResult(draft, response.model(), response.inputTokens(), response.outputTokens(),
				Duration.between(start, clock.instant()));
	}

}
