package com.kowalchuk.quoteservice.extraction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A draft submission read from a broker document: the same fields as a submission request,
 * but any of them may be missing, and each comes with flags and evidence for the reviewer.
 * <p>
 * This is deliberately not the generated API type or {@code NewSubmission}. A draft is allowed
 * to be incomplete; a submission is not. It becomes one only when a person confirms it, and then
 * it goes through the same rules as any other submission. Amounts are USD, the only accepted
 * currency.
 */
public record ExtractedSubmission(
		@Nullable String businessName,
		ExtractedAddress address,
		@Nullable String naicsCode,
		@Nullable BigDecimal annualRevenue,
		@Nullable BigDecimal perOccurrenceLimit,
		@Nullable BigDecimal aggregateLimit,
		@Nullable LocalDate effectiveDate,
		List<FieldFlag> flags,
		List<FieldEvidence> evidence) {

	public ExtractedSubmission {
		flags = List.copyOf(flags);
		evidence = List.copyOf(evidence);
	}

	/**
	 * The required fields (contract names, as used in flags) that are still empty. A draft can be
	 * confirmed only when this is empty; {@code address.line2} is optional and never listed.
	 */
	public List<String> missingRequiredFields() {
		List<String> missing = new ArrayList<>();
		addIfNull(missing, "businessName", businessName);
		addIfNull(missing, "address.line1", address.line1());
		addIfNull(missing, "address.city", address.city());
		addIfNull(missing, "address.region", address.region());
		addIfNull(missing, "address.postalCode", address.postalCode());
		addIfNull(missing, "address.country", address.country());
		addIfNull(missing, "naicsCode", naicsCode);
		addIfNull(missing, "annualRevenue", annualRevenue);
		addIfNull(missing, "perOccurrenceLimit", perOccurrenceLimit);
		addIfNull(missing, "aggregateLimit", aggregateLimit);
		addIfNull(missing, "effectiveDate", effectiveDate);
		return missing;
	}

	public boolean hasFlag(String field, FlagReason reason) {
		return flags.stream().anyMatch(flag -> flag.field().equals(field) && flag.reason() == reason);
	}

	private static void addIfNull(List<String> missing, String field, @Nullable Object value) {
		if (value == null) {
			missing.add(field);
		}
	}

}
