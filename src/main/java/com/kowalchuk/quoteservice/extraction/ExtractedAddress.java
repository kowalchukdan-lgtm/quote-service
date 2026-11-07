package com.kowalchuk.quoteservice.extraction;

import org.jspecify.annotations.Nullable;

/**
 * The risk location as read from a document. Every part may be missing; a draft is not a
 * contract-valid address until a reviewer completes it.
 */
public record ExtractedAddress(
		@Nullable String line1,
		@Nullable String line2,
		@Nullable String city,
		@Nullable String region,
		@Nullable String postalCode,
		@Nullable String country) {

	public static ExtractedAddress empty() {
		return new ExtractedAddress(null, null, null, null, null, null);
	}

}
