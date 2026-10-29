package com.kowalchuk.quoteservice.quote;

/**
 * A quote's status. {@code EXPIRED} is never stored: it is derived at read time by
 * {@link QuoteValidity}, and the database rejects it.
 */
public enum QuoteStatus {

	QUOTED, REFERRED, DECLINED, EXPIRED

}
