package com.kowalchuk.quoteservice.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import com.kowalchuk.quoteservice.api.model.MoneyDto;
import com.kowalchuk.quoteservice.api.model.QuoteDto;
import com.kowalchuk.quoteservice.api.model.QuoteStatusDto;
import com.kowalchuk.quoteservice.api.model.RatingFactorDto;
import com.kowalchuk.quoteservice.api.model.StatusReasonDto;
import com.kowalchuk.quoteservice.submission.Money;

/**
 * Translates quotes to the generated API model. Rate row IDs and the stored rating inputs are
 * internal audit data and are not exposed.
 * <p>
 * Never rounds: amounts and factors are formatted with {@link RoundingMode#UNNECESSARY}, which
 * throws if a value has more decimals than the contract allows.
 */
@Component
public class QuoteMapper {

	private final Clock clock;

	public QuoteMapper(Clock clock) {
		this.clock = clock;
	}

	public QuoteDto toDto(Quote quote) {
		QuoteDto dto = new QuoteDto(quote.getId(), quote.getSubmissionId(),
				QuoteStatusDto.valueOf(quote.statusAt(clock.instant()).name()),
				quote.getRatingFactors()
					.stream()
					.map(factor -> new RatingFactorDto(factor.code(), factor.description(), factor.inputValue(),
							factor(factor.factor())))
					.toList(),
				quote.getStatusReasons()
					.stream()
					.map(reason -> new StatusReasonDto(reason.code(), reason.message()))
					.toList(),
				utc(quote.getCreatedAt()));
		Money premium = quote.getPremium();
		if (premium != null) {
			dto.premium(new MoneyDto(premium.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
					premium.currency()));
		}
		Instant expiresAt = quote.getExpiresAt();
		if (expiresAt != null) {
			dto.expiresAt(utc(expiresAt));
		}
		return dto;
	}

	// "1.2500": four decimals, as in the contract's examples.
	private static String factor(BigDecimal factor) {
		return factor.setScale(4, RoundingMode.UNNECESSARY).toPlainString();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
