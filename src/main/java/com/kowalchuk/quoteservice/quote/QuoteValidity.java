package com.kowalchuk.quoteservice.quote;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * The only definition of whether a quote is still valid. {@code now} must always come from the
 * application's injected {@code Clock}, never from the database ({@code now()},
 * {@code CURRENT_TIMESTAMP}) or {@code Instant.now()} without a clock, so every check uses the
 * same time source and tests can control it.
 * <p>
 * The contract defines {@code expiresAt} as when the offer stops being bindable, so a quote is
 * valid only while {@code now} is before it: at exactly {@code expiresAt} it has expired.
 */
// TODO: Any query on quote validity (listing active quotes, binding) must be added here as a
// Specification taking now from the injected Clock, with a repository test proving it agrees
// with isBindable one microsecond before, at and after expiresAt. Binding must also re-check
// atomically (SELECT ... FOR UPDATE, or a conditional update built from that Specification).
public final class QuoteValidity {

	private QuoteValidity() {
	}

	public static boolean isBindable(QuoteStatus storedStatus, @Nullable Instant expiresAt, Instant now) {
		return storedStatus == QuoteStatus.QUOTED && expiresAt != null && now.isBefore(expiresAt);
	}

}
