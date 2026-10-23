package com.kowalchuk.quoteservice.submission;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A submitted risk. Immutable once created: there are no setters, and {@code @Immutable}
 * makes Hibernate ignore any accidental change.
 * <p>
 * Implements {@link Persistable} because the ID is assigned by the application: without it,
 * Spring Data sees a non-null ID, assumes the row exists, and merges (an extra SELECT)
 * instead of inserting.
 */
@Entity
@Immutable
@Table(name = "submissions")
public class Submission implements Persistable<String> {

	@Id
	private String id;

	@Column(name = "business_name", nullable = false)
	private String businessName;

	@Embedded
	private Address address;

	@ManyToOne(optional = false)
	@JoinColumn(name = "naics_code")
	private NaicsClass naicsClass;

	@Embedded
	@AttributeOverride(name = "amount",
			column = @Column(name = "annual_revenue_amount", precision = 15, scale = 2))
	@AttributeOverride(name = "currency", column = @Column(name = "annual_revenue_currency"))
	private Money annualRevenue;

	@Embedded
	@AttributeOverride(name = "amount",
			column = @Column(name = "per_occurrence_limit_amount", precision = 15, scale = 2))
	@AttributeOverride(name = "currency", column = @Column(name = "per_occurrence_limit_currency"))
	private Money perOccurrenceLimit;

	@Embedded
	@AttributeOverride(name = "amount",
			column = @Column(name = "aggregate_limit_amount", precision = 15, scale = 2))
	@AttributeOverride(name = "currency", column = @Column(name = "aggregate_limit_currency"))
	private Money aggregateLimit;

	@Column(name = "effective_date", nullable = false)
	private LocalDate effectiveDate;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Transient
	private boolean persisted;

	protected Submission() {
	}

	private Submission(String id, NewSubmission newSubmission, NaicsClass naicsClass, Instant createdAt) {
		this.id = id;
		this.businessName = newSubmission.businessName();
		this.address = newSubmission.address();
		this.naicsClass = naicsClass;
		this.annualRevenue = newSubmission.annualRevenue();
		this.perOccurrenceLimit = newSubmission.perOccurrenceLimit();
		this.aggregateLimit = newSubmission.aggregateLimit();
		this.effectiveDate = newSubmission.effectiveDate();
		this.createdAt = createdAt;
	}

	/**
	 * Creates a new, not yet persisted submission. {@code naicsClass} is the resolved class
	 * for {@code newSubmission.naicsCode()}.
	 */
	public static Submission create(String id, NewSubmission newSubmission, NaicsClass naicsClass,
			Instant createdAt) {
		return new Submission(id, newSubmission, naicsClass, createdAt);
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return !persisted;
	}

	@PostPersist
	@PostLoad
	void markPersisted() {
		this.persisted = true;
	}

	public String getBusinessName() {
		return businessName;
	}

	public Address getAddress() {
		return address;
	}

	public NaicsClass getNaicsClass() {
		return naicsClass;
	}

	public Money getAnnualRevenue() {
		return annualRevenue;
	}

	public Money getPerOccurrenceLimit() {
		return perOccurrenceLimit;
	}

	public Money getAggregateLimit() {
		return aggregateLimit;
	}

	public LocalDate getEffectiveDate() {
		return effectiveDate;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
