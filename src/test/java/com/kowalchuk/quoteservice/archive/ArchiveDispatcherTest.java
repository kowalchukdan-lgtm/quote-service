package com.kowalchuk.quoteservice.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.kowalchuk.quoteservice.archive.IntakeEvent.Channel;

@ExtendWith(OutputCaptureExtension.class)
class ArchiveDispatcherTest {

	private static final String PAYLOAD = "{\"businessName\":\"Coastal Taco Stand LLC\"}";

	private static final IntakeEvent.SubmissionReceived RECEIVED = new IntakeEvent.SubmissionReceived("sub_1",
			Instant.parse("2026-11-07T17:10:00Z"), Channel.API, null, PAYLOAD);

	private final IntakeArchive archive = mock(IntakeArchive.class);

	private ThreadPoolTaskExecutor executor = executor(1, 10);

	private ArchiveDispatcher dispatcher = new ArchiveDispatcher(archive, executor);

	@AfterEach
	void stop() {
		dispatcher.destroy();
	}

	@Test
	void writesOnTheArchivesOwnThread() {
		AtomicReference<String> thread = new AtomicReference<>();
		doAnswer(call -> {
			thread.set(Thread.currentThread().getName());
			return null;
		}).when(archive).append(RECEIVED);

		dispatcher.onIntakeEvent(RECEIVED);

		verify(archive, timeout(2000)).append(RECEIVED);
		assertThat(thread.get()).startsWith("test-archive-").isNotEqualTo(Thread.currentThread().getName());
	}

	@Test
	void aFailedWriteIsLoggedWithTheEntityButNotThePayload(CapturedOutput output) {
		doThrow(new IllegalStateException("echoes " + PAYLOAD)).when(archive).append(RECEIVED);

		dispatcher.write(RECEIVED);

		assertThat(output).contains("Archiving SUBMISSION_RECEIVED sub_1 failed: IllegalStateException")
			.doesNotContain("Coastal Taco");
	}

	@Test
	void aRefusedWriteIsLoggedWithTheStoresStatus(CapturedOutput output) {
		doThrow(new ArchiveWriteException(403)).when(archive).append(RECEIVED);

		dispatcher.write(RECEIVED);

		assertThat(output).contains("Archiving SUBMISSION_RECEIVED sub_1 failed: ArchiveWriteException (status 403)");
	}

	@Test
	void aFullQueueDropsTheEventWithoutBlocking(CapturedOutput output) throws InterruptedException {
		dispatcher.destroy();
		executor = executor(1, 1);
		dispatcher = new ArchiveDispatcher(archive, executor);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch writing = new CountDownLatch(1);
		doAnswer(call -> {
			writing.countDown();
			release.await(5, TimeUnit.SECONDS);
			return null;
		}).when(archive).append(RECEIVED);

		dispatcher.onIntakeEvent(RECEIVED); // runs, and blocks the only thread
		assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue();
		dispatcher.onIntakeEvent(RECEIVED); // fills the queue
		long start = System.nanoTime();
		dispatcher.onIntakeEvent(new IntakeEvent.ExtractionDrafted("ext_9", Instant.EPOCH, "m", "v", 0, 0,
				Duration.ZERO, PAYLOAD, "00", 1)); // dropped

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
		assertThat(output).contains("Archive queue full: dropped EXTRACTION_DRAFTED ext_9").doesNotContain("Coastal Taco");
		release.countDown();
	}

	@Test
	void anArchiveThatKeepsNothingAcceptsEverything() {
		new NoOpIntakeArchive().append(RECEIVED);
	}

	private static ThreadPoolTaskExecutor executor(int threads, int queue) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("test-archive-");
		executor.setCorePoolSize(threads);
		executor.setMaxPoolSize(threads);
		executor.setQueueCapacity(queue);
		executor.initialize();
		return executor;
	}

}
