package com.kowalchuk.quoteservice.extraction;

import org.jspecify.annotations.Nullable;

/**
 * A field the reviewer should check. {@code field} uses the extraction field names (for
 * example {@code annualRevenue} or {@code address.postalCode}), or {@code document} for a flag
 * about the document as a whole.
 */
public record FieldFlag(String field, FlagReason reason, @Nullable String note) {

	public static final String DOCUMENT = "document";

}
