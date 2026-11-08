package com.kowalchuk.quoteservice.archive;

/** The archive when none is configured, such as local runs and tests: it keeps nothing. */
public final class NoOpIntakeArchive implements IntakeArchive {

	@Override
	public void append(IntakeEvent event) {
		// Deliberately nothing.
	}

}
