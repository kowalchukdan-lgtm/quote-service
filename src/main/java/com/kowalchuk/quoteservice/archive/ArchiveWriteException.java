package com.kowalchuk.quoteservice.archive;

/**
 * An archive write the store refused. It carries the store's status code and never the event or
 * the store's own message, so logging it can't leak a payload.
 */
public class ArchiveWriteException extends RuntimeException {

	private final int statusCode;

	public ArchiveWriteException(int statusCode) {
		super("The archive store refused the write with status " + statusCode);
		this.statusCode = statusCode;
	}

	public int statusCode() {
		return statusCode;
	}

}
