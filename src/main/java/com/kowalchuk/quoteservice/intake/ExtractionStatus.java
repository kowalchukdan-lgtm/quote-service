package com.kowalchuk.quoteservice.intake;

public enum ExtractionStatus {

	/** Drafted from the document, waiting for a person to review it. */
	DRAFT,

	/** A person confirmed it, and a submission was created. */
	CONFIRMED

}
