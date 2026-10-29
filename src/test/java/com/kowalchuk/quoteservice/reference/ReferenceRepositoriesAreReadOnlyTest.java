package com.kowalchuk.quoteservice.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Quotes store the IDs of the rate rows they used, which is only evidence if those rows never
 * change. The database trigger is the backstop; this checks that application code has no way
 * to write them in the first place.
 */
class ReferenceRepositoriesAreReadOnlyTest {

	private static final List<String> WRITE_PREFIXES = List.of("save", "delete", "flush", "update");

	@ParameterizedTest
	@ValueSource(classes = { ClassRateRepository.class, LimitFactorRepository.class, RatingStateRepository.class })
	void repositoryHasNoWriteMethods(Class<?> repository) {
		List<String> writes = Arrays.stream(repository.getMethods())
			.map(Method::getName)
			.filter(name -> WRITE_PREFIXES.stream().anyMatch(name::startsWith))
			.toList();

		assertThat(writes).as("write methods on %s", repository.getSimpleName()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(classes = { ClassRate.class, LimitFactor.class, RatingState.class })
	void entityHasNoSettersOrPublicConstructors(Class<?> entity) {
		assertThat(Arrays.stream(entity.getMethods()).map(Method::getName).filter(name -> name.startsWith("set")))
			.isEmpty();
		assertThat(Arrays.stream(entity.getConstructors()).filter(c -> Modifier.isPublic(c.getModifiers())))
			.isEmpty();
	}

}
