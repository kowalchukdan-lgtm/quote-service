package com.kowalchuk.quoteservice.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kowalchuk.quoteservice.api.model.ExtractionDto;
import com.kowalchuk.quoteservice.api.model.ExtractionStatusDto;
import com.kowalchuk.quoteservice.extraction.ExtractedAddress;
import com.kowalchuk.quoteservice.extraction.ExtractedSubmission;

class ExtractionMapperTest {

	private final ExtractionMapper mapper = new ExtractionMapper();

	@Test
	void shapesTheDraftLikeASubmissionRequestWithPointersIntoIt() {
		ExtractionDto dto = mapper.toDto(ExtractionFixtures.draftExtraction("ext_1"), ExtractionFixtures.tacoStandDraft());

		assertThat(dto.getId()).isEqualTo("ext_1");
		assertThat(dto.getStatus()).isEqualTo(ExtractionStatusDto.DRAFT);
		assertThat(dto.getDocument().getFileName()).isEqualTo("taco.pdf");
		assertThat(dto.getDocument().getSizeBytes()).isEqualTo(3011);
		assertThat(dto.getDraft().getBusinessName()).isEqualTo("Coastal Taco Stand LLC");
		assertThat(dto.getDraft().getClassOfBusiness().getNaicsCode()).isEqualTo("722513");
		assertThat(dto.getDraft().getAnnualRevenue()).as("missing values are left out").isNull();
		assertThat(dto.getDraft().getRequestedLimit().getPerOccurrence().getAmount()).isEqualTo("1000000.00");
		assertThat(dto.getDraft().getRequestedLimit().getAggregate().getCurrency()).isEqualTo("USD");
		assertThat(dto.getMissingFields()).containsExactly("/annualRevenue");
		assertThat(dto.getFlags()).extracting(flag -> flag.getField()).containsExactly("/annualRevenue", "");
		assertThat(dto.getFlags().get(1).getNote()).isNull();
		assertThat(dto.getEvidence()).extracting(evidence -> evidence.getField())
			.containsExactly("/classOfBusiness/naicsCode", "/address/city");
		assertThat(dto.getModel()).isEqualTo("gpt-5-mini-2025-08-07");
		assertThat(dto.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-07T17:02:41Z"));
		assertThat(dto.getSubmissionId()).isNull();
		assertThat(dto.getConfirmedAt()).isNull();
	}

	@Test
	void aConfirmedExtractionNamesItsSubmission() {
		Extraction extraction = ExtractionFixtures.draftExtraction("ext_1");
		extraction.confirm("sub_1", Instant.parse("2026-11-07T17:09:15Z"));

		ExtractionDto dto = mapper.toDto(extraction, ExtractionFixtures.tacoStandDraft());

		assertThat(dto.getStatus()).isEqualTo(ExtractionStatusDto.CONFIRMED);
		assertThat(dto.getSubmissionId()).isEqualTo("sub_1");
		assertThat(dto.getConfirmedAt()).isEqualTo(OffsetDateTime.parse("2026-11-07T17:09:15Z"));
	}

	@Test
	void anEmptyDraftHasNoNestedObjects() {
		ExtractedSubmission empty = new ExtractedSubmission(null, ExtractedAddress.empty(), null, null, null, null,
				null, List.of(), List.of());

		ExtractionDto dto = mapper.toDto(ExtractionFixtures.draftExtraction("ext_1"), empty);

		assertThat(dto.getDraft().getAddress()).isNull();
		assertThat(dto.getDraft().getClassOfBusiness()).isNull();
		assertThat(dto.getDraft().getRequestedLimit()).isNull();
		assertThat(dto.getMissingFields()).hasSize(11).contains("/address/line1", "/classOfBusiness/naicsCode",
				"/requestedLimit/perOccurrence", "/effectiveDate");
	}

	@Test
	void roundTripsADraftThroughStorage() {
		DraftJson draftJson = new DraftJson();

		assertThat(draftJson.read(draftJson.write(ExtractionFixtures.tacoStandDraft())))
			.isEqualTo(ExtractionFixtures.tacoStandDraft());
	}

}
