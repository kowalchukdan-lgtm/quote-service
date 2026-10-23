package com.kowalchuk.quoteservice.submission;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record Address(
		@Column(name = "address_line1") String line1,
		@Column(name = "address_line2") @Nullable String line2,
		@Column(name = "address_city") String city,
		@Column(name = "address_region") String region,
		@Column(name = "address_postal_code") String postalCode,
		@Column(name = "address_country") String country) {
}
