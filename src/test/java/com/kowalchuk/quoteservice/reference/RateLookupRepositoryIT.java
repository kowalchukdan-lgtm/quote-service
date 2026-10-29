package com.kowalchuk.quoteservice.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.kowalchuk.quoteservice.TestcontainersConfiguration;

/**
 * The shared "row in effect on this date" lookup, run against Postgres once per rate table so
 * all three are shown to behave identically. Each test runs in a transaction that rolls back,
 * so rows added for real states and limit pairs never reach other tests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RateLookupRepositoryIT {

	private static final LocalDate ORIGINAL = LocalDate.of(2026, 1, 1);

	private static final LocalDate CHANGE = LocalDate.of(2026, 7, 1);

	// One test transaction gives every default loaded_at the same now(), so later loads are explicit.
	private static final OffsetDateTime LATER_LOAD = OffsetDateTime.now().plusMinutes(5);

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ClassRateRepository classRates;

	@Autowired
	LimitFactorRepository limitFactors;

	@Autowired
	RatingStateRepository ratingStates;

	enum Table {

		CLASS_RATES, LIMIT_FACTORS, RATING_STATES

	}

	@ParameterizedTest
	@EnumSource(Table.class)
	void rowInEffectIsTheLatestOnOrBeforeThePolicyDate(Table table) {
		long original = insert(table, Key.TEST, ORIGINAL, "1.0000", null, null);
		long changed = insert(table, Key.TEST, CHANGE, "2.0000", null, null);
		// A row for a different key on the change date must not affect the result.
		insert(table, Key.OTHER, CHANGE.plusDays(1), "9.0000", null, null);

		assertThat(lookup(table, Key.TEST, CHANGE.minusDays(1))).as("day before").contains(original);
		assertThat(lookup(table, Key.TEST, CHANGE)).as("day of").contains(changed);
		assertThat(lookup(table, Key.TEST, CHANGE.plusDays(1))).as("day after").contains(changed);
		assertThat(lookup(table, Key.TEST, ORIGINAL.minusDays(1))).as("before the first row").isEmpty();
	}

	@ParameterizedTest
	@EnumSource(Table.class)
	void correctionSupersedesTheRowItCorrects(Table table) {
		long original = insert(table, Key.TEST, ORIGINAL, "1.0000", null, null);
		long correction = insert(table, Key.TEST, ORIGINAL, "1.2000", original, LATER_LOAD);

		assertThat(lookup(table, Key.TEST, ORIGINAL)).contains(correction);
		assertThat(lookup(table, Key.TEST, CHANGE)).contains(correction);
	}

	@ParameterizedTest
	@EnumSource(Table.class)
	void laterDatedRowStillBeatsACorrectionOfAnEarlierDate(Table table) {
		long original = insert(table, Key.TEST, ORIGINAL, "1.0000", null, null);
		long changed = insert(table, Key.TEST, CHANGE, "2.0000", null, null);
		// Loaded after the change row, but for the earlier date: effective_from is compared first.
		long correction = insert(table, Key.TEST, ORIGINAL, "1.2000", original, LATER_LOAD);

		assertThat(lookup(table, Key.TEST, CHANGE.minusDays(1))).contains(correction);
		assertThat(lookup(table, Key.TEST, CHANGE)).contains(changed);
	}

	@ParameterizedTest
	@EnumSource(Table.class)
	void correctionMustTargetARowWithTheSameKeyAndDate(Table table) {
		long original = insert(table, Key.TEST, ORIGINAL, "1.0000", null, null);

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insert(table, Key.TEST, CHANGE, "1.2000", original, LATER_LOAD))
			.withMessageContaining("_corrects");
	}

	@ParameterizedTest
	@EnumSource(Table.class)
	void correctionNeedsAReason(Table table) {
		long original = insert(table, Key.TEST, ORIGINAL, "1.0000", null, null);
		String sql = switch (table) {
			case CLASS_RATES -> "INSERT INTO class_rates (naics_code, effective_from, base_rate_per_thousand, "
					+ "max_auto_revenue, corrects_id, loaded_at) VALUES ('999900', ?, 1.2, 5000000, ?, ?)";
			case LIMIT_FACTORS -> "INSERT INTO limit_factors (per_occurrence_limit, aggregate_limit, effective_from, "
					+ "factor, corrects_id, loaded_at) VALUES (3000000, 6000000, ?, 1.2, ?, ?)";
			case RATING_STATES -> "INSERT INTO rating_states (state_code, effective_from, minimum_premium, "
					+ "corrects_id, loaded_at) VALUES ('ZQ', ?, 501, ?, ?)";
		};

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> jdbc.update(sql, ORIGINAL, original, LATER_LOAD))
			.withMessageContaining("_correction_reason");
	}

	@ParameterizedTest
	@EnumSource(Table.class)
	void sameKeyDateAndLoadTimeIsRejected(Table table) {
		insert(table, Key.TEST, ORIGINAL, "1.0000", null, null);

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insert(table, Key.TEST, ORIGINAL, "1.2000", null, null));
	}

	@ParameterizedTest
	@EnumSource(Table.class)
	void rowsCannotBeUpdated(Table table) {
		long id = insert(table, Key.TEST, ORIGINAL, "1.0000", null, null);

		assertThatExceptionOfType(DataAccessException.class)
			.isThrownBy(() -> jdbc.update("UPDATE " + name(table) + " SET effective_from = ? WHERE id = ?", CHANGE, id))
			.withMessageContaining("append-only");
	}

	@ParameterizedTest
	@EnumSource(Table.class)
	void rowsCannotBeDeleted(Table table) {
		long id = insert(table, Key.TEST, ORIGINAL, "1.0000", null, null);

		assertThatExceptionOfType(DataAccessException.class)
			.isThrownBy(() -> jdbc.update("DELETE FROM " + name(table) + " WHERE id = ?", id))
			.withMessageContaining("append-only");
	}

	@Test
	void loadedAtIsSetByTheDatabase() {
		long id = insert(Table.CLASS_RATES, Key.TEST, ORIGINAL, "1.0000", null, null);

		assertThat(classRates.findById(id)).get().satisfies(row -> assertThat(row.getLoadedAt()).isNotNull());
	}

	private enum Key {

		TEST, OTHER

	}

	private long insert(Table table, Key key, LocalDate effectiveFrom, String value, @Nullable Long correctsId,
			@Nullable OffsetDateTime loadedAt) {
		String reason = (correctsId != null) ? "Test correction" : null;
		return switch (table) {
			case CLASS_RATES -> {
				String code = (key == Key.TEST) ? "999900" : "999901";
				jdbc.update("INSERT INTO naics_classes (code, title, in_appetite) VALUES (?, 'Test class', TRUE) "
						+ "ON CONFLICT DO NOTHING", code);
				yield jdbc.queryForObject("INSERT INTO class_rates (naics_code, effective_from, base_rate_per_thousand, "
						+ "max_auto_revenue, corrects_id, correction_reason, loaded_at) "
						+ "VALUES (?, ?, ?::numeric, 5000000, ?, ?, COALESCE(?, now())) RETURNING id", Long.class, code,
						effectiveFrom, value, correctsId, reason, loadedAt);
			}
			case LIMIT_FACTORS -> {
				long aggregate = (key == Key.TEST) ? 6000000 : 7000000;
				yield jdbc.queryForObject("INSERT INTO limit_factors (per_occurrence_limit, aggregate_limit, "
						+ "effective_from, factor, corrects_id, correction_reason, loaded_at) "
						+ "VALUES (3000000, ?, ?, ?::numeric, ?, ?, COALESCE(?, now())) RETURNING id", Long.class,
						aggregate, effectiveFrom, value, correctsId, reason, loadedAt);
			}
			case RATING_STATES -> {
				String state = (key == Key.TEST) ? "ZQ" : "ZX";
				yield jdbc.queryForObject("INSERT INTO rating_states (state_code, effective_from, minimum_premium, "
						+ "corrects_id, correction_reason, loaded_at) "
						+ "VALUES (?, ?, ?::numeric * 100, ?, ?, COALESCE(?, now())) RETURNING id", Long.class, state,
						effectiveFrom, value, correctsId, reason, loadedAt);
			}
		};
	}

	private Optional<Long> lookup(Table table, Key key, LocalDate date) {
		return switch (table) {
			case CLASS_RATES -> classRates.findInEffect((key == Key.TEST) ? "999900" : "999901", date)
				.map(EffectiveDatedRow::getId);
			case LIMIT_FACTORS -> limitFactors
				.findInEffect(new BigDecimal("3000000"),
						new BigDecimal((key == Key.TEST) ? "6000000" : "7000000"), date)
				.map(EffectiveDatedRow::getId);
			case RATING_STATES -> ratingStates.findInEffect((key == Key.TEST) ? "ZQ" : "ZX", date)
				.map(EffectiveDatedRow::getId);
		};
	}

	private static String name(Table table) {
		return table.name().toLowerCase();
	}

}
