package com.kowalchuk.quoteservice.config;

import org.springframework.stereotype.Component;

import com.github.f4b6a3.ulid.UlidCreator;

/**
 * Generates opaque resource IDs such as {@code sub_01JB7Q8N4K2M3P5R6S7T8V9W0X}: a type
 * prefix plus a ULID, so IDs sort by creation time without revealing row counts.
 */
@Component
public class IdGenerator {

	public String next(String prefix) {
		return prefix + "_" + UlidCreator.getMonotonicUlid();
	}

}
