package com.kowalchuk.quoteservice.e2e;

import java.util.List;
import java.util.Locale;

import com.kowalchuk.quoteservice.extraction.ExtractionFailedException;
import com.kowalchuk.quoteservice.extraction.GoldenSet;
import com.kowalchuk.quoteservice.extraction.GoldenSetScorer;
import com.kowalchuk.quoteservice.extraction.ModelClient;
import com.kowalchuk.quoteservice.extraction.ModelRequest;
import com.kowalchuk.quoteservice.extraction.ModelResponse;

/**
 * A stand-in model for end-to-end tests: it recognises a golden-set document by its business name
 * in the document text, and answers exactly as the golden set expects. Any other document fails
 * like a real model call would, so a test never runs on a made-up answer.
 */
public class GoldenSetModelClient implements ModelClient {

	static final String MODEL = "golden-set-fake";

	private final List<GoldenSet.Case> cases = GoldenSet.all();

	@Override
	public ModelResponse complete(ModelRequest request) {
		String text = normalise(request.userContent());
		return cases.stream()
			.filter(golden -> text.contains(normalise(String.valueOf(golden.value("businessName")))))
			.findFirst()
			.map(golden -> new ModelResponse(GoldenSetScorer.perfectModelAnswer(golden), MODEL, 0, 0))
			.orElseThrow(() -> new ExtractionFailedException(
					"The fake model only knows the golden-set documents; this isn't one of them."));
	}

	private static String normalise(String text) {
		return text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

}
