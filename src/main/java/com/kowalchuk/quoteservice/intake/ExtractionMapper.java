package com.kowalchuk.quoteservice.intake;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.kowalchuk.quoteservice.api.model.AddressDraftDto;
import com.kowalchuk.quoteservice.api.model.ClassOfBusinessDto;
import com.kowalchuk.quoteservice.api.model.EvidenceConfidenceDto;
import com.kowalchuk.quoteservice.api.model.ExtractionDocumentDto;
import com.kowalchuk.quoteservice.api.model.ExtractionDto;
import com.kowalchuk.quoteservice.api.model.ExtractionEvidenceDto;
import com.kowalchuk.quoteservice.api.model.ExtractionFlagDto;
import com.kowalchuk.quoteservice.api.model.ExtractionFlagReasonDto;
import com.kowalchuk.quoteservice.api.model.ExtractionStatusDto;
import com.kowalchuk.quoteservice.api.model.MoneyDto;
import com.kowalchuk.quoteservice.api.model.RequestedLimitDraftDto;
import com.kowalchuk.quoteservice.api.model.SubmissionDraftDto;
import com.kowalchuk.quoteservice.extraction.ExtractedAddress;
import com.kowalchuk.quoteservice.extraction.ExtractedSubmission;
import com.kowalchuk.quoteservice.extraction.FieldEvidence;
import com.kowalchuk.quoteservice.extraction.FieldFlag;

/**
 * Translates an extraction into the API's {@code Extraction}. The draft takes the shape of
 * {@code SubmissionRequest}, so a client can edit it and send it to confirm; flags, evidence and
 * missing fields point into it with JSON Pointers.
 */
@Component
public class ExtractionMapper {

	private static final String USD = "USD";

	/** Extraction field names to JSON Pointers into the draft ({@code document} is the whole document). */
	private static final Map<String, String> POINTERS = Map.ofEntries(
			Map.entry(FieldFlag.DOCUMENT, ""),
			Map.entry("naicsCode", "/classOfBusiness/naicsCode"),
			Map.entry("perOccurrenceLimit", "/requestedLimit/perOccurrence"),
			Map.entry("aggregateLimit", "/requestedLimit/aggregate"));

	public ExtractionDto toDto(Extraction extraction, ExtractedSubmission draft) {
		ExtractionDto dto = new ExtractionDto()
			.id(extraction.getId())
			.status(ExtractionStatusDto.fromValue(extraction.getStatus().name()))
			.document(new ExtractionDocumentDto()
				.fileName(extraction.getDocumentName())
				.sizeBytes(extraction.getDocumentSizeBytes())
				.sha256(extraction.getDocumentSha256()))
			.draft(toDraftDto(draft))
			.flags(draft.flags().stream().map(ExtractionMapper::toDto).toList())
			.evidence(draft.evidence().stream().map(ExtractionMapper::toDto).toList())
			.missingFields(draft.missingRequiredFields().stream().map(ExtractionMapper::pointer).toList())
			.model(extraction.getModel())
			.createdAt(utc(extraction.getCreatedAt()));
		if (extraction.getSubmissionId() != null) {
			dto.submissionId(extraction.getSubmissionId());
		}
		if (extraction.getConfirmedAt() != null) {
			dto.confirmedAt(utc(extraction.getConfirmedAt()));
		}
		return dto;
	}

	static String pointer(String field) {
		String known = POINTERS.get(field);
		return known != null ? known : "/" + field.replace('.', '/');
	}

	private static SubmissionDraftDto toDraftDto(ExtractedSubmission draft) {
		SubmissionDraftDto dto = new SubmissionDraftDto()
			.businessName(draft.businessName())
			.address(toDto(draft.address()))
			.annualRevenue(money(draft.annualRevenue()))
			.effectiveDate(draft.effectiveDate());
		if (draft.naicsCode() != null) {
			dto.classOfBusiness(new ClassOfBusinessDto().naicsCode(draft.naicsCode()));
		}
		MoneyDto perOccurrence = money(draft.perOccurrenceLimit());
		MoneyDto aggregate = money(draft.aggregateLimit());
		if (perOccurrence != null || aggregate != null) {
			dto.requestedLimit(new RequestedLimitDraftDto().perOccurrence(perOccurrence).aggregate(aggregate));
		}
		return dto;
	}

	private static @Nullable AddressDraftDto toDto(ExtractedAddress address) {
		if (address.equals(ExtractedAddress.empty())) {
			return null;
		}
		return new AddressDraftDto()
			.line1(address.line1())
			.line2(address.line2())
			.city(address.city())
			.region(address.region())
			.postalCode(address.postalCode())
			.country(address.country());
	}

	private static ExtractionFlagDto toDto(FieldFlag flag) {
		ExtractionFlagDto dto = new ExtractionFlagDto().field(pointer(flag.field()))
			.reason(ExtractionFlagReasonDto.fromValue(flag.reason().name()));
		if (flag.note() != null) {
			dto.note(flag.note());
		}
		return dto;
	}

	private static ExtractionEvidenceDto toDto(FieldEvidence evidence) {
		return new ExtractionEvidenceDto().field(pointer(evidence.field()))
			.sourceText(evidence.sourceText())
			.confidence(EvidenceConfidenceDto.fromValue(evidence.confidence().name()));
	}

	// Same format as everywhere else in the API: two decimals, never exponent notation.
	private static @Nullable MoneyDto money(@Nullable BigDecimal amount) {
		return amount == null ? null
				: new MoneyDto(amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(), USD);
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
