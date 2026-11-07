package com.kowalchuk.quoteservice.extraction;

/**
 * The document can't be turned into text: not a PDF, password-protected, too large, or a scan
 * with no text layer (OCR is a later step). Nothing was sent to the model.
 */
public class UnreadableDocumentException extends RuntimeException {

	public UnreadableDocumentException(String message) {
		super(message);
	}

	public UnreadableDocumentException(String message, Throwable cause) {
		super(message, cause);
	}

}
