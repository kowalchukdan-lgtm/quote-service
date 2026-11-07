package com.kowalchuk.quoteservice.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Keeps the model's output schema in step with the API contract. If a field is added to
 * {@code SubmissionRequest} in {@code quote-api.yaml} and not to the extraction schema, or the
 * other way round, this fails, so the AI's draft and the API can't drift apart.
 * <p>
 * It also checks the rules of strict structured outputs, which the model service enforces only at
 * call time: every object lists all its properties as required and allows no others.
 */
class ExtractionSchemaContractTest {

	/** Each contract property of SubmissionRequest and the extraction field(s) that carry it. */
	private static final Map<String, List<String>> CONTRACT_TO_EXTRACTION = Map.of(
			"businessName", List.of("businessName"),
			"address", List.of("address"),
			"classOfBusiness", List.of("naicsCode"),
			"annualRevenue", List.of("annualRevenue"),
			"requestedLimit", List.of("perOccurrenceLimit", "aggregateLimit"),
			"effectiveDate", List.of("effectiveDate"));

	private static final Set<String> REVIEW_FIELDS = Set.of("flags", "evidence");

	@Test
	void everyContractFieldHasAnExtractionFieldAndNothingElseIsExtracted() {
		Map<String, Object> request = contractSchema("SubmissionRequest");
		Map<String, Object> extraction = extractionSchema();

		assertThat(properties(request).keySet()).containsExactlyInAnyOrderElementsOf(CONTRACT_TO_EXTRACTION.keySet());

		List<String> mapped = new ArrayList<>(REVIEW_FIELDS);
		CONTRACT_TO_EXTRACTION.values().forEach(mapped::addAll);
		assertThat(properties(extraction).keySet()).containsExactlyInAnyOrderElementsOf(mapped);
	}

	@Test
	void addressPartsMatchTheContract() {
		Map<String, Object> contractAddress = contractSchema("Address");
		Map<String, Object> extractionAddress = object(properties(extractionSchema()).get("address"));

		assertThat(properties(extractionAddress).keySet())
			.containsExactlyInAnyOrderElementsOf(properties(contractAddress).keySet());
	}

	@Test
	void nestedContractObjectsHaveTheShapeTheFlatteningAssumes() {
		assertThat(properties(contractSchema("ClassOfBusiness")).keySet()).containsExactly("naicsCode");
		assertThat(properties(contractSchema("RequestedLimit")).keySet())
			.containsExactlyInAnyOrder("perOccurrence", "aggregate");
		assertThat(properties(contractSchema("Money")).keySet()).containsExactlyInAnyOrder("amount", "currency");
	}

	@Test
	void followsTheStrictStructuredOutputRules() {
		List<String> problems = new ArrayList<>();
		checkStrict("$", extractionSchema(), problems);

		assertThat(problems).isEmpty();
	}

	private static void checkStrict(String path, Map<String, Object> schema, List<String> problems) {
		Object type = schema.get("type");
		if ("object".equals(type)) {
			if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) {
				problems.add(path + ": additionalProperties must be false");
			}
			Map<String, Object> properties = properties(schema);
			Object required = schema.get("required");
			if (!(required instanceof List<?> list) || !Set.copyOf(list).equals(properties.keySet())) {
				problems.add(path + ": required must list every property");
			}
			properties.forEach((name, child) -> checkStrict(path + "." + name, object(child), problems));
		}
		if (schema.get("items") != null) {
			checkStrict(path + "[]", object(schema.get("items")), problems);
		}
		for (String unsupported : List.of("pattern", "format", "minLength", "maxLength")) {
			if (schema.containsKey(unsupported)) {
				problems.add(path + ": avoid '" + unsupported + "'; the parser checks formats instead");
			}
		}
	}

	private static Map<String, Object> contractSchema(String name) {
		Map<String, Object> spec = read("/openapi/quote-api.yaml", new YAMLMapper());
		return object(object(object(spec.get("components")).get("schemas")).get(name));
	}

	private static Map<String, Object> extractionSchema() {
		return read(ExtractionPrompt.SCHEMA_RESOURCE, JsonMapper.builder().build());
	}

	private static Map<String, Object> properties(Map<String, Object> schema) {
		return object(schema.get("properties"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> read(String resource, tools.jackson.databind.ObjectMapper mapper) {
		try (InputStream in = ExtractionSchemaContractTest.class.getResourceAsStream(resource)) {
			assertThat(in).as(resource).isNotNull();
			return mapper.readValue(in, Map.class);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
