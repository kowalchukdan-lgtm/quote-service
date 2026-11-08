package com.kowalchuk.quoteservice.archive.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.azure.core.credential.TokenCredential;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.kowalchuk.quoteservice.archive.ArchiveWriteException;
import com.kowalchuk.quoteservice.archive.IntakeEvent;
import com.kowalchuk.quoteservice.archive.IntakeEvent.Channel;

/**
 * The Cosmos adapter with the SDK mocked: no emulator and no Azure account.
 */
class CosmosIntakeArchiveTest {

	private static final Instant AT = Instant.parse("2026-11-07T17:10:00.123456Z");

	private static final IntakeEvent.SubmissionReceived RECEIVED = new IntakeEvent.SubmissionReceived("sub_1", AT,
			Channel.API, null, "{ \"businessName\" : \"Coastal Taco Stand LLC\" }");

	private static final IntakeEvent.ExtractionDrafted DRAFTED = new IntakeEvent.ExtractionDrafted("ext_1", AT,
			"gpt-5-mini-2025-08-07", "sha256:0123456789ab", 1304, 929, Duration.ofMillis(8400),
			"{\"businessName\":\"Coastal Taco Stand LLC\"}", "ab".repeat(32), 3011);

	private final CosmosClient client = mock(CosmosClient.class);

	private final CosmosDatabase database = mock(CosmosDatabase.class);

	private final CosmosContainer container = mock(CosmosContainer.class);

	private final AtomicInteger connections = new AtomicInteger();

	private final Supplier<CosmosClient> clients = () -> {
		connections.incrementAndGet();
		return client;
	};

	private final CosmosIntakeArchive archive = new CosmosIntakeArchive(clients, "quotes", "intake-archive");

	@BeforeEach
	void cosmosHasTheContainer() {
		when(client.getDatabase("quotes")).thenReturn(database);
		when(database.getContainer("intake-archive")).thenReturn(container);
	}

	@Test
	void aSubmissionIsArchivedAsReceivedInItsOwnPartition() {
		archive.append(RECEIVED);

		Map<String, Object> doc = created(new PartitionKey("sub_1"));
		assertThat(doc).containsExactly(Map.entry("id", "SUBMISSION_RECEIVED-sub_1"), Map.entry("entityId", "sub_1"),
				Map.entry("type", "SUBMISSION_RECEIVED"), Map.entry("schemaVersion", 1),
				Map.entry("occurredAt", "2026-11-07T17:10:00.123456Z"), Map.entry("channel", "API"),
				Map.entry("requestJson", "{ \"businessName\" : \"Coastal Taco Stand LLC\" }"));
	}

	@Test
	void aConfirmedDraftLinksToItsExtraction() {
		archive.append(new IntakeEvent.SubmissionReceived("sub_2", AT, Channel.EXTRACTION_CONFIRM, "ext_1", "{}"));

		assertThat(created(new PartitionKey("sub_2"))).containsEntry("channel", "EXTRACTION_CONFIRM")
			.containsEntry("extractionId", "ext_1");
	}

	@Test
	void anExtractionKeepsTheModelsAnswerAndItsCostButNotTheDocument() {
		archive.append(DRAFTED);

		Map<String, Object> doc = created(new PartitionKey("ext_1"));
		assertThat(doc).containsEntry("id", "EXTRACTION_DRAFTED-ext_1")
			.containsEntry("type", "EXTRACTION_DRAFTED")
			.containsEntry("model", "gpt-5-mini-2025-08-07")
			.containsEntry("promptVersion", "sha256:0123456789ab")
			.containsEntry("inputTokens", 1304)
			.containsEntry("outputTokens", 929)
			.containsEntry("elapsedMs", 8400L)
			.containsEntry("document", Map.of("sha256", "ab".repeat(32), "sizeBytes", 3011L))
			.containsEntry("modelResponse", "{\"businessName\":\"Coastal Taco Stand LLC\"}");
	}

	@Test
	void writesAreCreatesNeverUpserts() {
		archive.append(RECEIVED);

		verify(container).createItem(any(), any(PartitionKey.class), any(CosmosItemRequestOptions.class));
		verify(container, never()).upsertItem(any());
		verify(container, never()).upsertItem(any(), any(PartitionKey.class), any(CosmosItemRequestOptions.class));
	}

	@Test
	void aRepeatWriteIsAConflictAndCountsAsDone() {
		CosmosException conflict = cosmosError(409);
		when(container.createItem(any(), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
			.thenThrow(conflict);

		archive.append(RECEIVED);
	}

	@Test
	void otherErrorsAreReportedWithTheirStatusOnly() {
		CosmosException forbidden = cosmosError(403);
		when(container.createItem(any(), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
			.thenThrow(forbidden);

		assertThatThrownBy(() -> archive.append(RECEIVED)).isInstanceOf(ArchiveWriteException.class)
			.hasMessage("The archive store refused the write with status 403")
			.hasNoCause()
			.satisfies(ex -> assertThat(((ArchiveWriteException) ex).statusCode()).isEqualTo(403));
	}

	@Test
	void connectsOnceOnTheFirstWriteAndReusesTheClient() {
		assertThat(connections).hasValue(0);

		archive.append(RECEIVED);
		archive.append(DRAFTED);

		assertThat(connections).hasValue(1);
		verify(container, times(2)).createItem(any(), any(PartitionKey.class), any(CosmosItemRequestOptions.class));
	}

	@Test
	void aFailedConnectionIsTriedAgainOnTheNextWrite() {
		AtomicInteger attempts = new AtomicInteger();
		CosmosIntakeArchive flaky = new CosmosIntakeArchive(() -> {
			if (attempts.incrementAndGet() == 1) {
				throw new IllegalStateException("Cosmos is unreachable");
			}
			return client;
		}, "quotes", "intake-archive");

		assertThatThrownBy(() -> flaky.append(RECEIVED)).isInstanceOf(IllegalStateException.class);
		flaky.append(RECEIVED);

		assertThat(attempts).hasValue(2);
		verify(container).createItem(any(), eq(new PartitionKey("sub_1")), any(CosmosItemRequestOptions.class));
	}

	@Test
	void closingClosesTheClientOnlyIfItConnected() {
		archive.close();
		verify(client, never()).close();

		archive.append(RECEIVED);
		archive.close();
		verify(client).close();
	}

	@Test
	void theRealClientIsOnlyBuiltOnFirstUse() {
		// Building would contact the account; creating the archive must not.
		CosmosIntakeArchive real = CosmosIntakeArchive.connectingTo("https://cosmos.invalid:443/",
				mock(TokenCredential.class), "quotes", "intake-archive");

		assertThat(real).isNotNull();
		real.close();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> created(PartitionKey partitionKey) {
		ArgumentCaptor<Object> doc = ArgumentCaptor.forClass(Object.class);
		verify(container).createItem(doc.capture(), eq(partitionKey), any(CosmosItemRequestOptions.class));
		return (Map<String, Object>) doc.getValue();
	}

	private static CosmosException cosmosError(int status) {
		CosmosException error = mock(CosmosException.class);
		when(error.getStatusCode()).thenReturn(status);
		return error;
	}

}
