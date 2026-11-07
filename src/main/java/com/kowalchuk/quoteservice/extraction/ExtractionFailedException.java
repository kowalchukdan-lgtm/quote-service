package com.kowalchuk.quoteservice.extraction;

/**
 * The model call failed or returned something unusable (an error, a refusal, cut-off or
 * malformed output). The document itself may be fine, so a retry can succeed.
 */
public class ExtractionFailedException extends RuntimeException {

	public ExtractionFailedException(String message) {
		super(message);
	}

	public ExtractionFailedException(String message, Throwable cause) {
		super(message, cause);
	}

}
