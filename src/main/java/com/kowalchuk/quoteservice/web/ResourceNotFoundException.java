package com.kowalchuk.quoteservice.web;

/**
 * A requested resource does not exist. Rendered as 404 {@code not-found} with the message
 * as {@code detail}, so the message must be safe to show to clients.
 */
public abstract class ResourceNotFoundException extends RuntimeException {

	protected ResourceNotFoundException(String message) {
		super(message);
	}

}
