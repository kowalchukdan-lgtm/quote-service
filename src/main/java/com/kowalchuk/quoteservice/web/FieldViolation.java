package com.kowalchuk.quoteservice.web;

/**
 * One entry of a Problem's {@code errors} array.
 *
 * @param pointer JSON Pointer (RFC 6901) into the request body; {@code ""} is the whole body
 * @param detail what is wrong at that location
 */
public record FieldViolation(String pointer, String detail) {
}
