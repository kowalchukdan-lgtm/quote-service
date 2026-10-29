package com.kowalchuk.quoteservice.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

import com.kowalchuk.quoteservice.rating.RatingEngine;
import com.kowalchuk.quoteservice.rating.RatingResult;
import com.kowalchuk.quoteservice.rating.RatingResult.Declined;
import com.kowalchuk.quoteservice.rating.RatingResult.Quoted;
import com.kowalchuk.quoteservice.rating.RatingResult.Referred;
import com.kowalchuk.quoteservice.submission.Money;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A quote: the outcome of rating a submission, with everything needed to explain it later.
 * Immutable once created. Every quote records its rating inputs and the IDs of the rate rows
 * it consulted, whatever its outcome; a null row ID means no row was in effect.
 * <p>
 * Rate rows and the submission are referenced by ID only: the quote never navigates to them,
 * and the database foreign keys enforce integrity.
 */
@Entity
@Immutable
@Table(name = "quotes")
public class Quote implements Persistable<String> {

	// Matches NUMERIC(10,4): more decimals would be rounded silently by Postgres.
	private static final int MAX_FACTOR_SCALE = 4;

	@Id
	private String id;

	@Column(name = "submission_id", nullable = false)
	private String submissionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private QuoteStatus status;

	@Embedded
	private RatingInputs ratedInputs;

	@Column(name = "class_rate_id")
	private @Nullable Long classRateId;

	@Column(name = "limit_factor_id")
	private @Nullable Long limitFactorId;

	@Column(name = "rating_state_id")
	private @Nullable Long ratingStateId;

	@Embedded
	@AttributeOverride(name = "amount", column = @Column(name = "premium_amount", precision = 15, scale = 2))
	@AttributeOverride(name = "currency", column = @Column(name = "premium_currency"))
	private @Nullable Money premium;

	@Column(name = "minimum_premium", precision = 15, scale = 2)
	private @Nullable BigDecimal minimumPremium;

	@Column(name = "minimum_premium_applied")
	private @Nullable Boolean minimumPremiumApplied;

	@Column(name = "rating_plan_version", nullable = false)
	private String ratingPlanVersion;

	/**
	 * Application clock. Compare only with expiresAt and the injected Clock. The table's
	 * rates_read_at column (database clock, set by its default in the same transaction as the
	 * rate lookups) is deliberately not mapped: the application never uses it, and it exists
	 * only for SQL audit queries that compare it with rate rows' loaded_at.
	 */
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at")
	private @Nullable Instant expiresAt;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "quote_rating_factors", joinColumns = @JoinColumn(name = "quote_id"))
	@OrderColumn(name = "position")
	private List<RatingFactorSnapshot> ratingFactors = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "quote_status_reasons", joinColumns = @JoinColumn(name = "quote_id"))
	@OrderColumn(name = "position")
	private List<StatusReason> statusReasons = new ArrayList<>();

	@Transient
	private boolean persisted;

	protected Quote() {
	}

	/**
	 * Creates a new, not yet persisted quote from a rating result. {@code expiresAt} must be
	 * present exactly when the result is {@link Quoted}.
	 * @throws IllegalArgumentException if the premium is not exactly in cents, a factor has more
	 * than four decimals, or {@code expiresAt} does not match the outcome
	 */
	public static Quote create(String id, String submissionId, RatingInputs ratedInputs, RateRowIds rateRows,
			RatingResult result, String currency, Instant createdAt, @Nullable Instant expiresAt) {
		Quote quote = new Quote();
		quote.id = id;
		quote.submissionId = submissionId;
		quote.ratedInputs = ratedInputs;
		quote.classRateId = rateRows.classRateId();
		quote.limitFactorId = rateRows.limitFactorId();
		quote.ratingStateId = rateRows.ratingStateId();
		quote.ratingPlanVersion = RatingEngine.PLAN_VERSION;
		quote.createdAt = createdAt;
		switch (result) {
			case Quoted quoted -> {
				requireScale("premium", quoted.premium(), RatingEngine.PREMIUM_SCALE);
				if (quoted.minimumPremium().stripTrailingZeros().scale() > RatingEngine.PREMIUM_SCALE) {
					throw new IllegalArgumentException("Minimum premium has more than two decimals: " + quoted.minimumPremium());
				}
				if (expiresAt == null) {
					throw new IllegalArgumentException("A priced quote needs an expiry");
				}
				quote.status = QuoteStatus.QUOTED;
				quote.premium = new Money(quoted.premium(), currency);
				quote.minimumPremium = quoted.minimumPremium();
				quote.minimumPremiumApplied = quoted.minimumApplied();
				quote.expiresAt = expiresAt;
				for (RatingResult.AppliedFactor factor : quoted.factors()) {
					if (factor.factor().stripTrailingZeros().scale() > MAX_FACTOR_SCALE) {
						throw new IllegalArgumentException(
								"Factor " + factor.code() + " has more than four decimals: " + factor.factor());
					}
					quote.ratingFactors.add(new RatingFactorSnapshot(factor.code(), factor.description(),
							factor.inputValue(), factor.factor()));
				}
			}
			case Referred referred -> {
				quote.status = QuoteStatus.REFERRED;
				quote.statusReasons.addAll(reasons(referred.reasons()));
			}
			case Declined declined -> {
				quote.status = QuoteStatus.DECLINED;
				quote.statusReasons.addAll(reasons(declined.reasons()));
			}
		}
		if (quote.status != QuoteStatus.QUOTED && expiresAt != null) {
			throw new IllegalArgumentException("Only a priced quote can expire");
		}
		return quote;
	}

	/**
	 * The status as of {@code now}: a stored QUOTED that is no longer bindable is EXPIRED.
	 * {@code now} must come from the injected Clock; see {@link QuoteValidity}.
	 */
	public QuoteStatus statusAt(Instant now) {
		if (status == QuoteStatus.QUOTED && !QuoteValidity.isBindable(status, expiresAt, now)) {
			return QuoteStatus.EXPIRED;
		}
		return status;
	}

	private static void requireScale(String name, BigDecimal value, int scale) {
		if (value.scale() != scale) {
			throw new IllegalArgumentException(name + " must have exactly " + scale + " decimals: " + value);
		}
	}

	private static List<StatusReason> reasons(List<RatingResult.Reason> reasons) {
		return reasons.stream().map(reason -> new StatusReason(reason.code(), reason.message())).toList();
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

	public String getSubmissionId() {
		return submissionId;
	}

	/** The stored status. Use {@link #statusAt} for what clients see. */
	public QuoteStatus getStatus() {
		return status;
	}

	public RatingInputs getRatedInputs() {
		return ratedInputs;
	}

	public @Nullable Long getClassRateId() {
		return classRateId;
	}

	public @Nullable Long getLimitFactorId() {
		return limitFactorId;
	}

	public @Nullable Long getRatingStateId() {
		return ratingStateId;
	}

	public @Nullable Money getPremium() {
		return premium;
	}

	public @Nullable BigDecimal getMinimumPremium() {
		return minimumPremium;
	}

	public @Nullable Boolean getMinimumPremiumApplied() {
		return minimumPremiumApplied;
	}

	public String getRatingPlanVersion() {
		return ratingPlanVersion;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public @Nullable Instant getExpiresAt() {
		return expiresAt;
	}

	public List<RatingFactorSnapshot> getRatingFactors() {
		return List.copyOf(ratingFactors);
	}

	public List<StatusReason> getStatusReasons() {
		return List.copyOf(statusReasons);
	}

	/** The rate rows a quote consulted; each is null if no row was in effect. */
	public record RateRowIds(@Nullable Long classRateId, @Nullable Long limitFactorId, @Nullable Long ratingStateId) {
	}

}
