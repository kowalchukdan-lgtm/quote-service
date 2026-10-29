package com.kowalchuk.quoteservice.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EffectiveDateRuleTest {

	// Late in the UTC day, to show "today" is the UTC date of the injected clock.
	private final EffectiveDateRule rule = new EffectiveDateRule(
			Clock.fixed(Instant.parse("2026-10-23T23:59:00Z"), ZoneOffset.UTC));

	@ParameterizedTest(name = "today {0} days: in the past={1}")
	@CsvSource({ "-1, true", "0, false", "1, false" })
	void onlyDatesBeforeTodayAreInThePast(int daysFromToday, boolean inThePast) {
		LocalDate effectiveDate = LocalDate.of(2026, 10, 23).plusDays(daysFromToday);

		assertThat(rule.pastDateViolation(effectiveDate).isPresent()).isEqualTo(inThePast);
	}

	@Test
	void messageNamesTheDate() {
		assertThat(rule.pastDateViolation(LocalDate.of(2026, 10, 22)))
			.contains("Effective date 2026-10-22 is in the past.");
	}

	@Test
	void todayComesFromTheClock() {
		assertThat(rule.today()).isEqualTo(LocalDate.of(2026, 10, 23));
	}

}
