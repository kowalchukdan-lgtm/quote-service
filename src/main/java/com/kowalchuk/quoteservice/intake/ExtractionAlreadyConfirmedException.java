package com.kowalchuk.quoteservice.intake;

import org.jspecify.annotations.Nullable;

import com.kowalchuk.quoteservice.web.ProblemException;
import com.kowalchuk.quoteservice.web.ProblemType;

/** A draft can be confirmed once; a second confirmation would create a duplicate submission. */
public class ExtractionAlreadyConfirmedException extends ProblemException {

	public ExtractionAlreadyConfirmedException(String id, @Nullable String submissionId) {
		super(ProblemType.EXTRACTION_ALREADY_CONFIRMED,
				"Extraction " + id + " was already confirmed as submission " + submissionId + ".");
	}

}
