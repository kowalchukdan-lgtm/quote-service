package com.kowalchuk.quoteservice.web;

/**
 * An error that maps to exactly one contract problem type, with the message as
 * {@code detail}. The message is shown to clients, so it must be safe to show.
 */
public abstract class ProblemException extends RuntimeException {

	private final ProblemType type;

	protected ProblemException(ProblemType type, String message) {
		super(message);
		this.type = type;
	}

	public ProblemType getType() {
		return type;
	}

}
