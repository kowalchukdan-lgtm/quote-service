package com.kowalchuk.quoteservice.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.json.JsonMapper;

/**
 * The extraction golden set in {@code src/test/resources/extraction/golden}: fictional broker
 * documents, each with the draft a correct extraction should produce. See the README there.
 */
public final class GoldenSet {

	static final String FOLDER = "/extraction/golden/";

	public static final List<String> SAMPLES = List.of("sample-01-keystone-pipe-heating",
			"sample-02-brightline-code-works", "sample-03-sharp-and-co-barbers", "sample-04-coastal-taco-stand",
			"sample-05-permian-basin-drilling");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private GoldenSet() {
	}

	public static List<Case> all() {
		return SAMPLES.stream().map(GoldenSet::load).toList();
	}

	@SuppressWarnings("unchecked")
	public static Case load(String sample) {
		Map<String, Object> file = JSON.readValue(bytes(sample + ".expected.json"), Map.class);
		return new Case(sample, bytes(sample + ".pdf"), (Map<String, Object>) file.get("expected"),
				(List<Map<String, Object>>) file.get("expectedFlags"));
	}

	public static byte[] bytes(String fileName) {
		try (InputStream in = GoldenSet.class.getResourceAsStream(FOLDER + fileName)) {
			if (in == null) {
				throw new IllegalStateException("Missing golden-set file " + FOLDER + fileName);
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * One sample. {@code expected} is shaped like a submission request (nested money and limits);
	 * a field the document doesn't give is null.
	 */
	public record Case(String name, byte[] pdf, Map<String, Object> expected, List<Map<String, Object>> expectedFlags) {

		@SuppressWarnings("unchecked")
		public @Nullable Object value(String path) {
			Object current = expected;
			for (String part : path.split("\\.")) {
				if (!(current instanceof Map<?, ?> map)) {
					return null;
				}
				current = ((Map<String, Object>) map).get(part);
			}
			return current;
		}

	}

}
