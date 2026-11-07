package com.kowalchuk.quoteservice.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import com.kowalchuk.quoteservice.extraction.DocumentTextReader;
import com.kowalchuk.quoteservice.extraction.ExtractionFailedException;
import com.kowalchuk.quoteservice.extraction.ExtractionPrompt;
import com.kowalchuk.quoteservice.extraction.GoldenSet;
import com.kowalchuk.quoteservice.extraction.GoldenSetScorer;
import com.kowalchuk.quoteservice.extraction.ModelRequest;
import com.kowalchuk.quoteservice.extraction.ModelResponse;

/**
 * The end-to-end fake recognises each golden document from its real PDF text, and nothing else.
 */
class GoldenSetModelClientTest {

	static final List<String> SAMPLES = GoldenSet.SAMPLES;

	private final GoldenSetModelClient model = new GoldenSetModelClient();

	private final ExtractionPrompt prompt = new ExtractionPrompt();

	@ParameterizedTest
	@FieldSource("SAMPLES")
	void eachGoldenDocumentGetsItsOwnAnswer(String sample) {
		GoldenSet.Case golden = GoldenSet.load(sample);
		ModelRequest request = prompt.requestFor(new DocumentTextReader().read(golden.pdf()));

		ModelResponse response = model.complete(request);

		assertThat(response.content()).isEqualTo(GoldenSetScorer.perfectModelAnswer(golden));
		assertThat(response.model()).isEqualTo(GoldenSetModelClient.MODEL);
	}

	@Test
	void anyOtherDocumentFails() {
		ModelRequest request = prompt.requestFor("Invoice from Somebody Else Ltd");

		assertThatThrownBy(() -> model.complete(request)).isInstanceOf(ExtractionFailedException.class);
	}

}
