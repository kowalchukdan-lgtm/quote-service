package com.kowalchuk.quoteservice.submission;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * The one home for "the policy effective date must not be in the past", used when a submission
 * is created and again when it is quoted (a submission for tomorrow may be quoted next week).
 * A policy dated today is accepted.
 * <p>
 * "Today" is the UTC date of the injected Clock. Whether it should follow the broker's or the
 * risk's time zone is an open question (see README Deferred).
 */
@Component
public class EffectiveDateRule {

	private final Clock clock;

	public EffectiveDateRule(Clock clock) {
		this.clock = clock;
	}

	public LocalDate today() {
		return LocalDate.now(clock);
	}

	/**
	 * The violation message if {@code effectiveDate} is in the past. Callers supply the pointer,
	 * which depends on their request: {@code /effectiveDate} for a submission body, {@code ""}
	 * for a quote request, which has no body.
	 */
	public Optional<String> pastDateViolation(LocalDate effectiveDate) {
		return effectiveDate.isBefore(today())
				? Optional.of("Effective date " + effectiveDate + " is in the past.")
				: Optional.empty();
	}

}
