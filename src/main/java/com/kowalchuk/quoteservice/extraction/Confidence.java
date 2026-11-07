package com.kowalchuk.quoteservice.extraction;

/**
 * The model's own view of how clearly the document states a value. It is a hint for the
 * reviewer, not a probability, and the golden-set evaluation is what shows whether to trust it.
 */
public enum Confidence {

	HIGH, MEDIUM, LOW

}
