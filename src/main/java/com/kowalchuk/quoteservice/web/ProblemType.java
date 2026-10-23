package com.kowalchuk.quoteservice.web;

import java.util.Arrays;
import java.util.Optional;

import org.springframework.http.HttpStatus;

/**
 * Problem types from the contract. Clients branch on {@code type}; {@code title} is fixed
 * per type, and {@code detail} varies per occurrence.
 */
public enum ProblemType {

	VALIDATION_ERROR("validation-error", "Request failed schema validation", HttpStatus.BAD_REQUEST),
	MALFORMED_REQUEST("malformed-request", "Malformed request body", HttpStatus.BAD_REQUEST),
	NOT_FOUND("not-found", "Resource not found", HttpStatus.NOT_FOUND),
	METHOD_NOT_ALLOWED("method-not-allowed", "Method not allowed", HttpStatus.METHOD_NOT_ALLOWED),
	NOT_ACCEPTABLE("not-acceptable", "Not acceptable", HttpStatus.NOT_ACCEPTABLE),
	UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", "Unsupported media type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
	BUSINESS_RULE_VIOLATION("business-rule-violation", "Submission violates a business rule",
			HttpStatus.UNPROCESSABLE_CONTENT),
	INTERNAL_ERROR("internal-error", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
	SERVICE_UNAVAILABLE("service-unavailable", "Service unavailable", HttpStatus.SERVICE_UNAVAILABLE);

	private final String slug;

	private final String title;

	private final HttpStatus status;

	ProblemType(String slug, String title, HttpStatus status) {
		this.slug = slug;
		this.title = title;
		this.status = status;
	}

	public String slug() {
		return slug;
	}

	public String title() {
		return title;
	}

	public HttpStatus status() {
		return status;
	}

	/**
	 * The default type for a framework-generated problem with this status. A 400 from the
	 * framework (missing header, type mismatch) means the request broke the contract.
	 */
	static Optional<ProblemType> defaultFor(int status) {
		if (status == HttpStatus.BAD_REQUEST.value()) {
			return Optional.of(VALIDATION_ERROR);
		}
		return Arrays.stream(values()).filter(type -> type.status.value() == status).findFirst();
	}

}
