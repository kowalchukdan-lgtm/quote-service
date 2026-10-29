package com.kowalchuk.quoteservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every operation in {@code quote-api.yaml} must have a handler or be deliberately deferred.
 * <p>
 * The compiler only catches operations on generated interfaces that a controller already
 * implements; this test also catches operations under unimplemented tags, new tags, and
 * untagged operations.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ContractCoverageTest {

	/** Operations deliberately not implemented yet. Remove an entry when it ships. */
	static final Set<String> NOT_YET_IMPLEMENTED = Set.of();

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	@Test
	void everyContractOperationIsImplementedOrDeferred() {
		Map<String, String> operations = OpenApiContract.load().operations();
		Set<String> handled = handledEndpoints();

		Set<String> missing = operations.entrySet()
			.stream()
			.filter(operation -> !handled.contains(operation.getValue()))
			.map(Map.Entry::getKey)
			.filter(operationId -> !NOT_YET_IMPLEMENTED.contains(operationId))
			.collect(Collectors.toSet());
		assertThat(missing)
			.as("Operations in quote-api.yaml with no handler; implement them or add them to NOT_YET_IMPLEMENTED")
			.isEmpty();

		Set<String> stale = NOT_YET_IMPLEMENTED.stream()
			.filter(operationId -> !operations.containsKey(operationId)
					|| handled.contains(operations.get(operationId)))
			.collect(Collectors.toSet());
		assertThat(stale).as("NOT_YET_IMPLEMENTED entries that are implemented or no longer in the contract")
			.isEmpty();
	}

	private Set<String> handledEndpoints() {
		return handlerMapping.getHandlerMethods()
			.keySet()
			.stream()
			.flatMap(ContractCoverageTest::endpoints)
			.collect(Collectors.toSet());
	}

	private static Stream<String> endpoints(RequestMappingInfo info) {
		return info.getMethodsCondition()
			.getMethods()
			.stream()
			.flatMap(method -> info.getPatternValues().stream().map(pattern -> method.name() + " " + pattern));
	}

}
