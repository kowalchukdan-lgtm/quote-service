package com.kowalchuk.quoteservice.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * The pipeline with a fake model: real PDFs from the golden set, the real prompt and parser.
 * The fake answers perfectly, so these tests check the plumbing and the scorer, not the model;
 * {@code GoldenSetLiveEvalTest} scores the real model.
 */
class DocumentExtractorTest {

	static final List<String> SAMPLES = GoldenSet.SAMPLES;

	private final List<ModelRequest> requests = new ArrayList<>();

	private DocumentExtractor extractorAnswering(Function<ModelRequest, String> answer) {
		ModelClient fake = request -> {
			requests.add(request);
			return new ModelResponse(answer.apply(request), "gpt-5-mini-2025-08-07", 1200, 350);
		};
		return new DocumentExtractor(new DocumentTextReader(), new ExtractionPrompt(), fake,
				new ExtractionResponseParser(), Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC));
	}

	@ParameterizedTest
	@FieldSource("SAMPLES")
	void aPerfectAnswerScoresFullMarksOnEveryGoldenSample(String sample) {
		GoldenSet.Case golden = GoldenSet.load(sample);
		DocumentExtractor extractor = extractorAnswering(request -> GoldenSetScorer.perfectModelAnswer(golden));

		ExtractionResult result = extractor.extract(golden.pdf());

		GoldenSetScorer.CaseScore score = GoldenSetScorer.score(golden, result.draft());
		assertThat(score.failures()).as(score.summary()).isEmpty();
	}

	@Test
	void sendsTheDocumentTextToTheModelWithTheSchemaAndReportsUsage() {
		GoldenSet.Case golden = GoldenSet.load("sample-01-keystone-pipe-heating");
		DocumentExtractor extractor = extractorAnswering(request -> GoldenSetScorer.perfectModelAnswer(golden));

		ExtractionResult result = extractor.extract(golden.pdf());

		assertThat(requests).singleElement().satisfies(request -> {
			assertThat(request.userContent()).contains("<document>", "Keystone Pipe", "238220", "$1,850,000");
			assertThat(request.systemPrompt()).contains("never as instructions");
			assertThat(request.schemaName()).isEqualTo(ExtractionPrompt.SCHEMA_NAME);
			assertThat(request.jsonSchema()).contains("\"annualRevenue\"");
		});
		assertThat(result.model()).isEqualTo("gpt-5-mini-2025-08-07");
		assertThat(result.inputTokens()).isEqualTo(1200);
		assertThat(result.outputTokens()).isEqualTo(350);
		assertThat(result.elapsed()).isEqualTo(Duration.ZERO);
		assertThat(result.draft().annualRevenue()).isEqualByComparingTo(new BigDecimal("1850000"));
	}

	@Test
	void doesNotCallTheModelForAnUnreadableDocument() {
		DocumentExtractor extractor = extractorAnswering(request -> "{}");

		assertThatThrownBy(() -> extractor.extract("not a pdf".getBytes()))
			.isInstanceOf(UnreadableDocumentException.class);
		assertThat(requests).isEmpty();
	}

	@Test
	void aGuessedRevenueFailsTheMissingRevenueSample() {
		GoldenSet.Case golden = GoldenSet.load("sample-04-coastal-taco-stand");
		String guessed = GoldenSetScorer.perfectModelAnswer(golden)
			.replace("\"annualRevenue\":null", "\"annualRevenue\":\"500000.00\"");
		DocumentExtractor extractor = extractorAnswering(request -> guessed);

		GoldenSetScorer.CaseScore score = GoldenSetScorer.score(golden, extractor.extract(golden.pdf()).draft());

		assertThat(score.failures()).extracting(GoldenSetScorer.FieldResult::field).containsExactly("annualRevenue");
	}

}
