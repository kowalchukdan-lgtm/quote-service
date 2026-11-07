package com.kowalchuk.quoteservice.extraction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Scores a draft against a golden-set answer, field by field. The rules are in the golden-set
 * README: amounts compare as decimals; names, streets and cities ignore case, commas, periods and
 * extra spaces; codes, states, ZIPs and dates must match exactly. A field the document doesn't give
 * passes only if the draft leaves it empty and flags it MISSING, so a guess always fails.
 */
public final class GoldenSetScorer {

	private enum Compare {

		TEXT, EXACT, AMOUNT

	}

	private record Field(String name, String expectedPath, Compare compare,
			Function<ExtractedSubmission, @Nullable Object> actual) {
	}

	private static final List<Field> FIELDS = List.of(
			new Field("businessName", "businessName", Compare.TEXT, ExtractedSubmission::businessName),
			new Field("address.line1", "address.line1", Compare.TEXT, draft -> draft.address().line1()),
			new Field("address.line2", "address.line2", Compare.TEXT, draft -> draft.address().line2()),
			new Field("address.city", "address.city", Compare.TEXT, draft -> draft.address().city()),
			new Field("address.region", "address.region", Compare.EXACT, draft -> draft.address().region()),
			new Field("address.postalCode", "address.postalCode", Compare.EXACT,
					draft -> draft.address().postalCode()),
			new Field("address.country", "address.country", Compare.EXACT, draft -> draft.address().country()),
			new Field("naicsCode", "classOfBusiness.naicsCode", Compare.EXACT, ExtractedSubmission::naicsCode),
			new Field("annualRevenue", "annualRevenue.amount", Compare.AMOUNT, ExtractedSubmission::annualRevenue),
			new Field("perOccurrenceLimit", "requestedLimit.perOccurrence.amount", Compare.AMOUNT,
					ExtractedSubmission::perOccurrenceLimit),
			new Field("aggregateLimit", "requestedLimit.aggregate.amount", Compare.AMOUNT,
					ExtractedSubmission::aggregateLimit),
			new Field("effectiveDate", "effectiveDate", Compare.EXACT, ExtractedSubmission::effectiveDate));

	private GoldenSetScorer() {
	}

	public static CaseScore score(GoldenSet.Case golden, ExtractedSubmission draft) {
		List<FieldResult> results = new ArrayList<>();
		for (Field field : FIELDS) {
			Object expected = golden.value(field.expectedPath());
			Object actual = field.actual().apply(draft);
			boolean pass;
			if (expected == null) {
				// line2 is optional: absent from the document simply means empty.
				boolean mustFlag = !field.name().equals("address.line2");
				pass = actual == null && (!mustFlag || draft.hasFlag(field.name(), FlagReason.MISSING));
			}
			else {
				pass = actual != null && matches(field.compare(), String.valueOf(expected), String.valueOf(actual));
			}
			results.add(new FieldResult(field.name(), expected == null ? null : String.valueOf(expected),
					actual == null ? null : String.valueOf(actual), pass));
		}
		return new CaseScore(golden.name(), results);
	}

	private static boolean matches(Compare compare, String expected, String actual) {
		return switch (compare) {
			case EXACT -> expected.equals(actual);
			case AMOUNT -> new BigDecimal(expected).compareTo(new BigDecimal(actual)) == 0;
			case TEXT -> normalize(expected).equals(normalize(actual));
		};
	}

	static String normalize(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[.,]", "").replaceAll("\\s+", " ").strip();
	}

	public record FieldResult(String field, @Nullable String expected, @Nullable String actual, boolean pass) {
	}

	public record CaseScore(String name, List<FieldResult> fields) {

		public long passed() {
			return fields.stream().filter(FieldResult::pass).count();
		}

		public List<FieldResult> failures() {
			return fields.stream().filter(result -> !result.pass()).toList();
		}

		public String summary() {
			StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%-36s %2d/%d%n", name, passed(),
					fields.size()));
			for (FieldResult failure : failures()) {
				out.append(String.format(Locale.ROOT, "    %-20s expected %s, got %s%n", failure.field(),
						failure.expected(), failure.actual()));
			}
			return out.toString();
		}

	}

	/** Field accuracy across cases, for the evaluation's pass mark. */
	public static double accuracy(List<CaseScore> scores) {
		long passed = scores.stream().mapToLong(CaseScore::passed).sum();
		long total = scores.stream().mapToLong(score -> score.fields().size()).sum();
		return total == 0 ? 0 : (double) passed / total;
	}

	/** Builds the model JSON that would produce exactly the golden answer; for tests of the pipeline. */
	@SuppressWarnings("unchecked")
	static String perfectModelAnswer(GoldenSet.Case golden) {
		Map<String, Object> address = (Map<String, Object>) golden.value("address");
		StringBuilder flags = new StringBuilder();
		for (Map<String, Object> flag : golden.expectedFlags()) {
			if (!flags.isEmpty()) {
				flags.append(',');
			}
			flags.append("{\"field\":").append(quote(flag.get("field"))).append(",\"reason\":")
				.append(quote(flag.get("reason"))).append(",\"note\":").append(quote(flag.get("note"))).append('}');
		}
		return "{\"businessName\":" + quote(golden.value("businessName")) + ",\"address\":{\"line1\":"
				+ quote(address.get("line1")) + ",\"line2\":" + quote(address.get("line2")) + ",\"city\":"
				+ quote(address.get("city")) + ",\"region\":" + quote(address.get("region")) + ",\"postalCode\":"
				+ quote(address.get("postalCode")) + ",\"country\":" + quote(address.get("country"))
				+ "},\"naicsCode\":" + quote(golden.value("classOfBusiness.naicsCode")) + ",\"annualRevenue\":"
				+ quote(golden.value("annualRevenue.amount")) + ",\"perOccurrenceLimit\":"
				+ quote(golden.value("requestedLimit.perOccurrence.amount")) + ",\"aggregateLimit\":"
				+ quote(golden.value("requestedLimit.aggregate.amount")) + ",\"effectiveDate\":"
				+ quote(golden.value("effectiveDate")) + ",\"flags\":[" + flags + "],\"evidence\":[]}";
	}

	private static String quote(@Nullable Object value) {
		return value == null ? "null" : "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

}
