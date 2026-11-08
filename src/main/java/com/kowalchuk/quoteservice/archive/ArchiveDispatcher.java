package com.kowalchuk.quoteservice.archive;

import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hands intake events to the archive, best-effort: only after the transaction that produced them
 * commits (a rollback archives nothing), and on the archive's own threads, so the request never
 * waits for the archive or sees it fail.
 * <p>
 * Events can be lost: a full queue drops them, a failed write isn't retried, and a crash loses
 * what was queued. That's acceptable for an audit copy; the outbox pattern would guarantee
 * delivery (see the README). Logs name the event and entity, never the payload.
 */
public class ArchiveDispatcher implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(ArchiveDispatcher.class);

	private final IntakeArchive archive;

	private final ThreadPoolTaskExecutor executor;

	/**
	 * @param executor bounded, and initialised; it is shut down with this dispatcher
	 */
	public ArchiveDispatcher(IntakeArchive archive, ThreadPoolTaskExecutor executor) {
		this.archive = archive;
		this.executor = executor;
	}

	/** Without a transaction (fallback), the caller's work is already committed. */
	@TransactionalEventListener(fallbackExecution = true)
	public void onIntakeEvent(IntakeEvent event) {
		try {
			executor.execute(() -> write(event));
		}
		catch (RejectedExecutionException ex) {
			log.warn("Archive queue full: dropped {} {}", event.type(), event.entityId());
		}
	}

	void write(IntakeEvent event) {
		try {
			archive.append(event);
		}
		catch (RuntimeException ex) {
			// The exception's message isn't logged: a store's message could echo the payload.
			log.warn("Archiving {} {} failed: {}", event.type(), event.entityId(), describe(ex));
		}
	}

	private static String describe(RuntimeException ex) {
		String type = ex.getClass().getSimpleName();
		return ex instanceof ArchiveWriteException write ? type + " (status " + write.statusCode() + ")" : type;
	}

	/** Lets queued writes finish, for as long as the executor's await-termination allows. */
	@Override
	public void destroy() {
		executor.shutdown();
	}

}
