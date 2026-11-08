package com.kowalchuk.quoteservice.archive;

/**
 * Append-only storage for raw intake events, kept for audit. Postgres stays the system of
 * record; nothing reads the archive back. The Cosmos DB adapter lives in {@code archive.cosmos};
 * without it, {@link NoOpIntakeArchive} keeps nothing.
 * <p>
 * Callers never call this directly: {@link ArchiveDispatcher} does, after commit and off the
 * request thread, so an implementation may be slow or fail without affecting a request.
 */
public interface IntakeArchive {

	/**
	 * Stores the event once; storing the same event again changes nothing.
	 * @throws ArchiveWriteException or any other runtime exception if it couldn't be stored
	 */
	void append(IntakeEvent event);

}
