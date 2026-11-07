package com.kowalchuk.quoteservice.intake;

import com.kowalchuk.quoteservice.web.ProblemException;
import com.kowalchuk.quoteservice.web.ProblemType;

/** Extraction isn't configured on this instance (no AI endpoint), so new drafts can't be made. */
public class ExtractionUnavailableException extends ProblemException {

	public ExtractionUnavailableException() {
		super(ProblemType.SERVICE_UNAVAILABLE, "Document extraction is not enabled on this service.");
	}

}
