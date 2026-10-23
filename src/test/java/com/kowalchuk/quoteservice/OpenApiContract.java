package com.kowalchuk.quoteservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Test-side view of {@code quote-api.yaml}: lists its operations and checks responses
 * against it. OpenAPI 3.1 schemas are JSON Schema 2020-12, so the spec document is
 * registered with a 2020-12 validator and schemas are addressed by JSON Pointer into it.
 */
public final class OpenApiContract {

	private static final String SPEC_URI = "https://quote-service.test/quote-api.json";

	private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head",
			"options", "trace");

	private final JsonNode spec;

	private final SchemaRegistry schemas;

	private OpenApiContract(JsonNode spec) {
		this.spec = spec;
		String specJson = spec.toString();
		this.schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
				builder -> builder.schemas(Map.of(SPEC_URI, specJson)));
	}

	public static OpenApiContract load() {
		try (InputStream yaml = new ClassPathResource("openapi/quote-api.yaml").getInputStream()) {
			return new OpenApiContract(new YAMLMapper().readTree(yaml));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Every operation in the contract, as operationId to {@code "METHOD /path/{template}"}.
	 */
	public Map<String, String> operations() {
		Map<String, String> operations = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
			for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
				if (HTTP_METHODS.contains(operation.getKey())) {
					operations.put(operation.getValue().path("operationId").asString(),
							operation.getKey().toUpperCase() + " " + path.getKey());
				}
			}
		}
		return operations;
	}

	/**
	 * A deep copy of an example from {@code components/examples}, for building requests.
	 */
	public ObjectNode example(String name) {
		JsonNode value = spec.path("components").path("examples").path(name).path("value");
		assertThat(value.isObject()).as("example %s exists", name).isTrue();
		return (ObjectNode) value.deepCopy();
	}

	/**
	 * Asserts that the contract declares the response's status and content type for this
	 * operation and that the body matches the declared schema.
	 */
	public void assertResponseMatches(String method, String pathTemplate, MvcResult result) {
		int status = result.getResponse().getStatus();
		String operationPointer = "/paths/" + escape(pathTemplate) + "/" + method.toLowerCase();
		assertThat(spec.at(operationPointer).isMissingNode()).as("%s %s is in the contract", method, pathTemplate)
			.isFalse();

		String responsePointer = operationPointer + "/responses/" + status;
		JsonNode response = spec.at(responsePointer);
		if (response.isMissingNode()) {
			fail("Status %d is not declared for %s %s", status, method, pathTemplate);
		}
		if (response.has("$ref")) {
			responsePointer = response.get("$ref").asString().substring(1);
			response = spec.at(responsePointer);
		}

		String contentType = result.getResponse().getContentType();
		assertThat(contentType).as("response content type").isNotNull();
		MediaType mediaType = MediaType.parseMediaType(contentType);
		String declaredType = mediaType.getType() + "/" + mediaType.getSubtype();
		assertThat(response.path("content").has(declaredType))
			.as("%s is declared for %d on %s %s", declaredType, status, method, pathTemplate)
			.isTrue();

		Schema schema = schemas
			.getSchema(SchemaLocation.of(SPEC_URI + "#" + responsePointer + "/content/" + escape(declaredType) + "/schema"));
		String body = contentAsString(result);
		List<Error> errors = schema.validate(body, InputFormat.JSON);
		assertThat(errors).as("%d response from %s %s matches the contract schema; body: %s", status, method,
				pathTemplate, body)
			.isEmpty();
	}

	private static String contentAsString(MvcResult result) {
		try {
			return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String escape(String token) {
		return token.replace("~", "~0").replace("/", "~1");
	}

}
