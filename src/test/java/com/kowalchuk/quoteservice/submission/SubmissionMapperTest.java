package com.kowalchuk.quoteservice.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kowalchuk.quoteservice.api.model.AddressDto;
import com.kowalchuk.quoteservice.api.model.ClassOfBusinessDto;
import com.kowalchuk.quoteservice.api.model.MoneyDto;
import com.kowalchuk.quoteservice.api.model.RequestedLimitDto;
import com.kowalchuk.quoteservice.api.model.SubmissionDto;
import com.kowalchuk.quoteservice.api.model.SubmissionRequestDto;

/**
 * Guards the mapper against contract growth: the null sweeps fail when the contract adds a
 * field that the fixture or the mapper does not carry through.
 */
class SubmissionMapperTest {

	private static final Instant CREATED_AT = Instant.parse("2026-10-23T14:05:12.123456Z");

	private static final NaicsClass RESTAURANTS = new NaicsClass("722511", "Full-Service Restaurants", true);

	private final SubmissionMapper mapper = new SubmissionMapper();

	@Test
	void roundTripCarriesEveryField() {
		SubmissionRequestDto request = fullyPopulatedRequest();
		// Fails first when the contract adds a request field: populate it here.
		assertThat(nullProperties(request, "request")).as("fixture must set every request property").isEmpty();

		NewSubmission newSubmission = mapper.toNewSubmission(request);
		// Fails when the mapper drops a request field.
		assertThat(nullProperties(newSubmission, "newSubmission")).isEmpty();

		SubmissionDto response = mapper.toDto(Submission.create("sub_TEST", newSubmission, RESTAURANTS, CREATED_AT));
		// Fails when the contract adds a response field the mapper never sets.
		assertThat(nullProperties(response, "response")).isEmpty();

		assertThat(response.getId()).isEqualTo("sub_TEST");
		assertThat(response.getBusinessName()).isEqualTo("Lone Star Smokehouse LLC");
		AddressDto address = response.getAddress();
		assertThat(address.getLine1()).isEqualTo("1200 Congress Ave");
		assertThat(address.getLine2()).isEqualTo("Suite 300");
		assertThat(address.getCity()).isEqualTo("Austin");
		assertThat(address.getRegion()).isEqualTo("TX");
		assertThat(address.getPostalCode()).isEqualTo("78701-1234");
		assertThat(address.getCountry()).isEqualTo("US");
		assertThat(response.getClassOfBusiness().getNaicsCode()).isEqualTo("722511");
		assertThat(response.getClassOfBusiness().getDescription()).isEqualTo("Full-Service Restaurants");
		// "1200000" in, "1200000.00" out: always two fractional digits.
		assertThat(response.getAnnualRevenue().getAmount()).isEqualTo("1200000.00");
		assertThat(response.getAnnualRevenue().getCurrency()).isEqualTo("USD");
		assertThat(response.getRequestedLimit().getPerOccurrence().getAmount()).isEqualTo("1000000.00");
		assertThat(response.getRequestedLimit().getAggregate().getAmount()).isEqualTo("2000000.00");
		assertThat(response.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-10-23T14:05:12.123456Z"));
		assertThat(response.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
	}

	/**
	 * Leading zeros pass the contract's amount pattern. The value is preserved; only the text
	 * form is normalised.
	 */
	@Test
	void leadingZeroAmountsKeepTheirValueButNormaliseTheirText() {
		SubmissionRequestDto request = fullyPopulatedRequest();
		request.getAnnualRevenue().setAmount("0001200000");
		request.getRequestedLimit().getPerOccurrence().setAmount("0001200000");
		request.getRequestedLimit().getAggregate().setAmount("0001200000");

		NewSubmission newSubmission = mapper.toNewSubmission(request);
		assertThat(List.of(newSubmission.annualRevenue(), newSubmission.perOccurrenceLimit(),
				newSubmission.aggregateLimit()))
			.allSatisfy(money -> assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("1200000")));

		SubmissionDto response = mapper.toDto(Submission.create("sub_TEST", newSubmission, RESTAURANTS, CREATED_AT));
		assertThat(List.of(response.getAnnualRevenue(), response.getRequestedLimit().getPerOccurrence(),
				response.getRequestedLimit().getAggregate()))
			.extracting(MoneyDto::getAmount)
			.containsOnly("1200000.00");
	}

	private static SubmissionRequestDto fullyPopulatedRequest() {
		return new SubmissionRequestDto("Lone Star Smokehouse LLC",
				new AddressDto("1200 Congress Ave", "Austin", "TX", "78701-1234", "US").line2("Suite 300"),
				new ClassOfBusinessDto("722511"), new MoneyDto("1200000", "USD"),
				new RequestedLimitDto(new MoneyDto("1000000.00", "USD"), new MoneyDto("2000000.00", "USD")),
				LocalDate.of(2026, 11, 1));
	}

	/**
	 * Paths of null properties, recursing into this project's types: record components for
	 * records, public getters otherwise.
	 */
	private static List<String> nullProperties(Object object, String path) {
		List<String> nulls = new ArrayList<>();
		for (Method accessor : accessors(object.getClass())) {
			Object value = invoke(accessor, object);
			String propertyPath = path + "." + accessor.getName();
			if (value == null) {
				nulls.add(propertyPath);
			}
			else if (value.getClass().getPackageName().startsWith("com.kowalchuk.quoteservice")) {
				nulls.addAll(nullProperties(value, propertyPath));
			}
		}
		return nulls;
	}

	private static List<Method> accessors(Class<?> type) {
		if (type.isRecord()) {
			return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getAccessor).toList();
		}
		return Arrays.stream(type.getMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()))
			.filter(method -> method.getParameterCount() == 0 && method.getName().startsWith("get"))
			.filter(method -> method.getDeclaringClass() != Object.class)
			.toList();
	}

	private static Object invoke(Method accessor, Object target) {
		try {
			return accessor.invoke(target);
		}
		catch (IllegalAccessException | InvocationTargetException ex) {
			throw new IllegalStateException("Could not read " + accessor, ex);
		}
	}

}
