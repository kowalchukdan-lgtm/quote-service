package com.kowalchuk.quoteservice.reference;

import java.time.Instant;
import java.time.LocalDate;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * A row of effective-dated, append-only rate data. Rows are inserted only by Flyway
 * migrations and never change: a rate change is a row with a later {@code effectiveFrom}, and
 * a correction is a row with the same {@code effectiveFrom} that names the row it corrects.
 * Quotes store the IDs of the rows they used, which is only evidence because rows never change.
 */
@MappedSuperclass
public abstract class EffectiveDatedRow {

	@Id
	private Long id;

	@Column(name = "effective_from", nullable = false)
	private LocalDate effectiveFrom;

	@Column(name = "corrects_id")
	private @Nullable Long correctsId;

	@Column(name = "correction_reason")
	private @Nullable String correctionReason;

	/** Database clock; compare only with other database timestamps, such as a quote's ratesReadAt. */
	@Column(name = "loaded_at", nullable = false, insertable = false, updatable = false)
	private Instant loadedAt;

	protected EffectiveDatedRow() {
	}

	public Long getId() {
		return id;
	}

	public LocalDate getEffectiveFrom() {
		return effectiveFrom;
	}

	public @Nullable Long getCorrectsId() {
		return correctsId;
	}

	public @Nullable String getCorrectionReason() {
		return correctionReason;
	}

	public Instant getLoadedAt() {
		return loadedAt;
	}

}
