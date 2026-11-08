package com.kowalchuk.quoteservice.archive.cosmos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azure.core.credential.TokenCredential;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.kowalchuk.quoteservice.archive.ArchiveWriteException;
import com.kowalchuk.quoteservice.archive.IntakeArchive;
import com.kowalchuk.quoteservice.archive.IntakeEvent;
import com.kowalchuk.quoteservice.archive.IntakeEvent.ExtractionDrafted;
import com.kowalchuk.quoteservice.archive.IntakeEvent.SubmissionReceived;

/**
 * The intake archive in Azure Cosmos DB (NoSQL API): one JSON document per event, partitioned by
 * {@code /entityId}.
 * <ul>
 * <li><b>Append-only:</b> documents are created, never upserted, with a deterministic ID, so a
 * repeat write is a 409 that changes nothing and counts as done.</li>
 * <li><b>Entra ID only:</b> it signs in with a token credential; there is no key option.</li>
 * <li><b>Never creates anything:</b> the database and container are made with the Azure CLI. If
 * they're missing, writes fail with 404 and are logged.</li>
 * <li><b>Lazy:</b> the client connects on the first write, on the archive's thread, so the app
 * starts even if Cosmos is unreachable. A failed connection is tried again on the next write.</li>
 * </ul>
 */
public class CosmosIntakeArchive implements IntakeArchive, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(CosmosIntakeArchive.class);

	static final int SCHEMA_VERSION = 1;

	private static final int CONFLICT = 409;

	private final Supplier<CosmosClient> clients;

	private final String database;

	private final String container;

	private @Nullable CosmosClient client;

	private @Nullable CosmosContainer items;

	CosmosIntakeArchive(Supplier<CosmosClient> clients, String database, String container) {
		this.clients = clients;
		this.database = database;
		this.container = container;
	}

	/** Gateway mode (HTTPS only), which suits Container Apps' outbound networking. */
	public static CosmosIntakeArchive connectingTo(String endpoint, TokenCredential credential, String database,
			String container) {
		return new CosmosIntakeArchive(() -> new CosmosClientBuilder().endpoint(endpoint)
			.credential(credential)
			.gatewayMode()
			.contentResponseOnWriteEnabled(false)
			.buildClient(), database, container);
	}

	@Override
	public void append(IntakeEvent event) {
		try {
			items().createItem(document(event), new PartitionKey(event.entityId()), new CosmosItemRequestOptions());
		}
		catch (CosmosException ex) {
			if (ex.getStatusCode() == CONFLICT) {
				log.info("{} {} was already archived", event.type(), event.entityId());
				return;
			}
			throw new ArchiveWriteException(ex.getStatusCode());
		}
	}

	private synchronized CosmosContainer items() {
		if (items == null) {
			CosmosClient connected = clients.get();
			// getDatabase and getContainer only build references; they never create anything.
			items = connected.getDatabase(database).getContainer(container);
			client = connected;
		}
		return items;
	}

	static Map<String, Object> document(IntakeEvent event) {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("id", event.type() + "-" + event.entityId());
		doc.put("entityId", event.entityId());
		doc.put("type", event.type().name());
		doc.put("schemaVersion", SCHEMA_VERSION);
		doc.put("occurredAt", event.occurredAt().toString());
		switch (event) {
			case SubmissionReceived received -> {
				doc.put("channel", received.channel().name());
				if (received.extractionId() != null) {
					doc.put("extractionId", received.extractionId());
				}
				doc.put("requestJson", received.requestJson());
			}
			case ExtractionDrafted drafted -> {
				doc.put("model", drafted.model());
				doc.put("promptVersion", drafted.promptVersion());
				doc.put("inputTokens", drafted.inputTokens());
				doc.put("outputTokens", drafted.outputTokens());
				doc.put("elapsedMs", drafted.elapsed().toMillis());
				doc.put("document", Map.of("sha256", drafted.documentSha256(), "sizeBytes", drafted.documentSizeBytes()));
				doc.put("modelResponse", drafted.modelResponse());
			}
		}
		return doc;
	}

	@Override
	public synchronized void close() {
		if (client != null) {
			client.close();
		}
	}

}
