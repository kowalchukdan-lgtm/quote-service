package com.kowalchuk.quoteservice.web;

import java.util.List;

import tools.jackson.core.JacksonException;

/**
 * Builds RFC 6901 JSON Pointers for the {@code errors[].pointer} field.
 */
final class JsonPointers {

	private JsonPointers() {
	}

	/**
	 * Converts a Bean Validation property path such as {@code address.postalCode} or
	 * {@code items[0].name} to {@code /address/postalCode} or {@code /items/0/name}.
	 */
	static String fromPropertyPath(String propertyPath) {
		if (propertyPath == null || propertyPath.isEmpty()) {
			return "";
		}
		StringBuilder pointer = new StringBuilder();
		for (String segment : propertyPath.replace("[", ".").replace("]", "").split("\\.")) {
			if (!segment.isEmpty()) {
				pointer.append('/').append(escape(segment));
			}
		}
		return pointer.toString();
	}

	/**
	 * Converts the path Jackson records on a parse failure to a pointer.
	 */
	static String fromJacksonPath(List<JacksonException.Reference> path) {
		StringBuilder pointer = new StringBuilder();
		for (JacksonException.Reference reference : path) {
			pointer.append('/');
			if (reference.getPropertyName() != null) {
				pointer.append(escape(reference.getPropertyName()));
			}
			else {
				pointer.append(reference.getIndex());
			}
		}
		return pointer.toString();
	}

	private static String escape(String token) {
		return token.replace("~", "~0").replace("/", "~1");
	}

}
