package com.kowalchuk.quoteservice.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ExtractionResponseParserTest {

	private final ExtractionResponseParser parser = new ExtractionResponseParser();

	private static String answer(String revenue, String region, String postalCode, String country, String date,
			String extra) {
		return """
				{"businessName": " Keystone Pipe & Heating LLC ",
				 "address": {"line1": "2215 Tilghman Street", "line2": "", "city": "Allentown",
				             "region": %s, "postalCode": %s, "country": %s},
				 "naicsCode": "238220", "annualRevenue": %s,
				 "perOccurrenceLimit": "1000000.00", "aggregateLimit": "2000000.00",
				 "effectiveDate": %s %s}
				""".formatted(region, postalCode, country, revenue, date, extra);
	}

	private static String clean(String extra) {
		return answer("\"1850000.00\"", "\"PA\"", "\"18104\"", "\"US\"", "\"2026-12-01\"", extra);
	}

	@Test
	void parsesACompleteAnswer() {
		ExtractedSubmission draft = parser.parse(clean("""
				, "flags": [],
				  "evidence": [{"field": "annualRevenue", "sourceText": "Annual gross revenue $1,850,000",
				                "confidence": "HIGH"}]"""));

		assertThat(draft.businessName()).isEqualTo("Keystone Pipe & Heating LLC");
		assertThat(draft.address()).isEqualTo(
				new ExtractedAddress("2215 Tilghman Street", null, "Allentown", "PA", "18104", "US"));
		assertThat(draft.naicsCode()).isEqualTo("238220");
		assertThat(draft.annualRevenue()).isEqualTo(new BigDecimal("1850000.00"));
		assertThat(draft.perOccurrenceLimit()).isEqualTo(new BigDecimal("1000000.00"));
		assertThat(draft.aggregateLimit()).isEqualTo(new BigDecimal("2000000.00"));
		assertThat(draft.effectiveDate()).isEqualTo(LocalDate.of(2026, 12, 1));
		assertThat(draft.flags()).isEmpty();
		assertThat(draft.evidence()).containsExactly(
				new FieldEvidence("annualRevenue", "Annual gross revenue $1,850,000", Confidence.HIGH));
		assertThat(draft.missingRequiredFields()).isEmpty();
	}

	@Test
	void flagsAMissingFieldEvenWhenTheModelForgets() {
		ExtractedSubmission draft = parser.parse(answer("null", "\"FL\"", "\"33605\"", "\"US\"", "\"2026-12-15\"",
				", \"flags\": [], \"evidence\": []"));

		assertThat(draft.annualRevenue()).isNull();
		assertThat(draft.missingRequiredFields()).containsExactly("annualRevenue");
		assertThat(draft.flags()).containsExactly(
				new FieldFlag("annualRevenue", FlagReason.MISSING, "Not found in the document."));
	}

	@Test
	void keepsTheModelsOwnMissingFlagWithoutAddingADuplicate() {
		ExtractedSubmission draft = parser.parse(answer("null", "\"FL\"", "\"33605\"", "\"US\"", "\"2026-12-15\"",
				", \"flags\": [{\"field\": \"annualRevenue\", \"reason\": \"MISSING\", \"note\": \"Financials to follow.\"}]"));

		assertThat(draft.flags()).containsExactly(
				new FieldFlag("annualRevenue", FlagReason.MISSING, "Financials to follow."));
	}

	@Test
	void discardsValuesThatBreakTheContractAndFlagsThemInvalid() {
		ExtractedSubmission draft = parser.parse(answer("\"1.85 million\"", "\"Pennsylvania\"", "\"1810\"",
				"\"Canada\"", "\"12/01/2026\"", ""));

		assertThat(draft.annualRevenue()).isNull();
		assertThat(draft.address().region()).isNull();
		assertThat(draft.address().postalCode()).isNull();
		assertThat(draft.address().country()).isNull();
		assertThat(draft.effectiveDate()).isNull();
		assertThat(draft.flags()).extracting(FieldFlag::field, FieldFlag::reason)
			.containsExactlyInAnyOrder(
					tuple("annualRevenue", FlagReason.INVALID),
					tuple("address.region", FlagReason.INVALID),
					tuple("address.postalCode", FlagReason.INVALID),
					tuple("address.country", FlagReason.INVALID),
					tuple("effectiveDate", FlagReason.INVALID));
	}

	@Test
	void cleansUpOnlyMechanicalFormatting() {
		ExtractedSubmission draft = parser.parse(answer("\"$1,850,000\"", "\"pa\"", "\"18104-1234\"", "\"USA\"",
				"\"2026-12-01\"", ""));

		assertThat(draft.annualRevenue()).isEqualTo(new BigDecimal("1850000.00"));
		assertThat(draft.address().region()).isEqualTo("PA");
		assertThat(draft.address().postalCode()).isEqualTo("18104-1234");
		assertThat(draft.address().country()).isEqualTo("US");
		assertThat(draft.flags()).isEmpty();
	}

	@Test
	void rejectsAnOverlongName() {
		String json = clean("").replace("\" Keystone Pipe & Heating LLC \"", "\"" + "N".repeat(201) + "\"");

		ExtractedSubmission draft = parser.parse(json);

		assertThat(draft.businessName()).isNull();
		assertThat(draft.hasFlag("businessName", FlagReason.INVALID)).isTrue();
	}

	@Test
	void keepsSuspectedInstructionFlagsAndToleratesUnknownLabels() {
		ExtractedSubmission draft = parser.parse(clean("""
				, "flags": [{"field": "document", "reason": "SUSPECTED_INSTRUCTION", "note": "Remarks address an automated system."},
				            {"field": "naicsCode", "reason": "SOMETHING_NEW", "note": null},
				            {"field": " ", "reason": "MISSING", "note": null}],
				  "evidence": [{"field": "naicsCode", "sourceText": "%s", "confidence": "SURE"},
				               {"field": "city", "sourceText": "   ", "confidence": "HIGH"}]"""
			.formatted("x".repeat(250))));

		assertThat(draft.flags()).containsExactly(
				new FieldFlag(FieldFlag.DOCUMENT, FlagReason.SUSPECTED_INSTRUCTION, "Remarks address an automated system."),
				new FieldFlag("naicsCode", FlagReason.AMBIGUOUS, null));
		assertThat(draft.evidence()).singleElement().satisfies(evidence -> {
			assertThat(evidence.confidence()).isEqualTo(Confidence.LOW);
			assertThat(evidence.sourceText()).hasSize(201).endsWith("…");
		});
	}

	@Test
	void treatsAMissingAddressObjectAsEmpty() {
		ExtractedSubmission draft = parser.parse("""
				{"businessName": "Acme", "naicsCode": "722511"}""");

		assertThat(draft.address()).isEqualTo(ExtractedAddress.empty());
		assertThat(draft.missingRequiredFields()).contains("address.line1", "annualRevenue", "effectiveDate")
			.doesNotContain("address.line2", "businessName");
	}

	@Test
	void failsOnAnswersThatAreNotUsableJson() {
		assertThatThrownBy(() -> parser.parse("Sorry, I can't help with that."))
			.isInstanceOf(ExtractionFailedException.class);
		assertThatThrownBy(() -> parser.parse("null")).isInstanceOf(ExtractionFailedException.class);
	}

}
