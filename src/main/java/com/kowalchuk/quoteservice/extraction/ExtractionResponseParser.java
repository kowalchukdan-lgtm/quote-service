package com.kowalchuk.quoteservice.extraction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the model's JSON into a draft, and never trusts it. Strict mode guarantees the shape,
 * not the values, so every value is checked against the same formats as the API contract. A value
 * that fails is discarded and flagged INVALID rather than passed on, and any required field that
 * ends up empty is flagged MISSING even if the model forgot to say so.
 * <p>
 * Only safe, mechanical clean-up happens here (trimming, "$" and thousands separators, "USA" to
 * "US"). Interpretation, such as "$4.2 million", is the model's job, and the golden set checks it.
 */
public class ExtractionResponseParser {

	private static final JsonMapper JSON = JsonMapper.builder()
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
		.build();

	// Formats from quote-api.yaml.
	private static final Pattern AMOUNT = Pattern.compile("^\\d{1,13}(\\.\\d{1,2})?$");

	private static final Pattern STATE = Pattern.compile("^[A-Z]{2}$");

	private static final Pattern ZIP = Pattern.compile("^\\d{5}(-\\d{4})?$");

	private static final Pattern NAICS = Pattern.compile("^\\d{6}$");

	private static final Set<String> US_ALIASES = Set.of("USA", "U.S.", "U.S.A.", "UNITED STATES",
			"UNITED STATES OF AMERICA");

	private static final int MAX_NAME = 200;

	private static final int MAX_ADDRESS_PART = 100;

	private static final int MAX_EVIDENCE = 200;

	public ExtractedSubmission parse(String modelJson) {
		RawExtraction raw;
		try {
			raw = JSON.readValue(modelJson, RawExtraction.class);
		}
		catch (JacksonException ex) {
			throw new ExtractionFailedException("The model returned JSON that doesn't match the extraction schema.",
					ex);
		}
		if (raw == null) {
			throw new ExtractionFailedException("The model returned an empty answer.");
		}

		List<FieldFlag> flags = new ArrayList<>(modelFlags(raw.flags()));
		RawAddress rawAddress = raw.address() != null ? raw.address()
				: new RawAddress(null, null, null, null, null, null);

		ExtractedAddress address = new ExtractedAddress(
				text("address.line1", rawAddress.line1(), MAX_ADDRESS_PART, flags),
				text("address.line2", rawAddress.line2(), MAX_ADDRESS_PART, flags),
				text("address.city", rawAddress.city(), MAX_ADDRESS_PART, flags),
				matching("address.region", upper(rawAddress.region()), STATE, flags),
				matching("address.postalCode", rawAddress.postalCode(), ZIP, flags),
				matching("address.country", country(rawAddress.country()), STATE, flags));

		ExtractedSubmission parsed = new ExtractedSubmission(
				text("businessName", raw.businessName(), MAX_NAME, flags),
				address,
				matching("naicsCode", raw.naicsCode(), NAICS, flags),
				amount("annualRevenue", raw.annualRevenue(), flags),
				amount("perOccurrenceLimit", raw.perOccurrenceLimit(), flags),
				amount("aggregateLimit", raw.aggregateLimit(), flags),
				date("effectiveDate", raw.effectiveDate(), flags),
				List.of(),
				evidence(raw.evidence()));

		for (String field : parsed.missingRequiredFields()) {
			boolean explained = flags.stream()
				.anyMatch(flag -> flag.field().equals(field)
						&& (flag.reason() == FlagReason.MISSING || flag.reason() == FlagReason.INVALID));
			if (!explained) {
				flags.add(new FieldFlag(field, FlagReason.MISSING, "Not found in the document."));
			}
		}
		return new ExtractedSubmission(parsed.businessName(), parsed.address(), parsed.naicsCode(),
				parsed.annualRevenue(), parsed.perOccurrenceLimit(), parsed.aggregateLimit(), parsed.effectiveDate(),
				flags, parsed.evidence());
	}

	private static @Nullable String text(String field, @Nullable String value, int maxLength, List<FieldFlag> flags) {
		String trimmed = blankToNull(value);
		if (trimmed != null && trimmed.length() > maxLength) {
			flags.add(new FieldFlag(field, FlagReason.INVALID, "Longer than " + maxLength + " characters."));
			return null;
		}
		return trimmed;
	}

	private static @Nullable String matching(String field, @Nullable String value, Pattern format,
			List<FieldFlag> flags) {
		String trimmed = blankToNull(value);
		if (trimmed != null && !format.matcher(trimmed).matches()) {
			flags.add(new FieldFlag(field, FlagReason.INVALID, "\"" + trimmed + "\" is not in the expected format."));
			return null;
		}
		return trimmed;
	}

	private static @Nullable BigDecimal amount(String field, @Nullable String value, List<FieldFlag> flags) {
		String cleaned = blankToNull(value);
		if (cleaned == null) {
			return null;
		}
		cleaned = cleaned.replace("$", "").replace(",", "").replace(" ", "");
		if (!AMOUNT.matcher(cleaned).matches()) {
			flags.add(new FieldFlag(field, FlagReason.INVALID, "\"" + value + "\" is not a plain USD amount."));
			return null;
		}
		return new BigDecimal(cleaned).setScale(2, RoundingMode.UNNECESSARY);
	}

	private static @Nullable LocalDate date(String field, @Nullable String value, List<FieldFlag> flags) {
		String trimmed = blankToNull(value);
		if (trimmed == null) {
			return null;
		}
		try {
			return LocalDate.parse(trimmed);
		}
		catch (DateTimeParseException ex) {
			flags.add(new FieldFlag(field, FlagReason.INVALID, "\"" + trimmed + "\" is not a yyyy-MM-dd date."));
			return null;
		}
	}

	private static @Nullable String country(@Nullable String value) {
		String upper = upper(value);
		return upper != null && US_ALIASES.contains(upper) ? "US" : upper;
	}

	private static @Nullable String upper(@Nullable String value) {
		String trimmed = blankToNull(value);
		return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
	}

	private static @Nullable String blankToNull(@Nullable String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static List<FieldFlag> modelFlags(@Nullable List<RawFlag> raw) {
		if (raw == null) {
			return List.of();
		}
		return raw.stream()
			.filter(flag -> flag != null && blankToNull(flag.field()) != null)
			.map(flag -> new FieldFlag(flag.field().strip(), reason(flag.reason()), blankToNull(flag.note())))
			.distinct()
			.toList();
	}

	private static FlagReason reason(@Nullable String value) {
		try {
			return value == null ? FlagReason.AMBIGUOUS : FlagReason.valueOf(value.strip());
		}
		catch (IllegalArgumentException ex) {
			return FlagReason.AMBIGUOUS;
		}
	}

	private static List<FieldEvidence> evidence(@Nullable List<RawEvidence> raw) {
		if (raw == null) {
			return List.of();
		}
		return raw.stream()
			.filter(item -> item != null && blankToNull(item.field()) != null && blankToNull(item.sourceText()) != null)
			.map(item -> new FieldEvidence(item.field().strip(), truncate(item.sourceText().strip()),
					confidence(item.confidence())))
			.toList();
	}

	private static String truncate(String text) {
		return text.length() <= MAX_EVIDENCE ? text : text.substring(0, MAX_EVIDENCE) + "…";
	}

	private static Confidence confidence(@Nullable String value) {
		try {
			return value == null ? Confidence.LOW : Confidence.valueOf(value.strip());
		}
		catch (IllegalArgumentException ex) {
			return Confidence.LOW;
		}
	}

	record RawExtraction(@Nullable String businessName, @Nullable RawAddress address, @Nullable String naicsCode,
			@Nullable String annualRevenue, @Nullable String perOccurrenceLimit, @Nullable String aggregateLimit,
			@Nullable String effectiveDate, @Nullable List<RawFlag> flags, @Nullable List<RawEvidence> evidence) {
	}

	record RawAddress(@Nullable String line1, @Nullable String line2, @Nullable String city,
			@Nullable String region, @Nullable String postalCode, @Nullable String country) {
	}

	record RawFlag(String field, @Nullable String reason, @Nullable String note) {
	}

	record RawEvidence(String field, String sourceText, @Nullable String confidence) {
	}

}
