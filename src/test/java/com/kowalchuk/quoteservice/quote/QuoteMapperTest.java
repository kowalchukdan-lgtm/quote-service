package com.kowalchuk.quoteservice.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.kowalchuk.quoteservice.api.model.QuoteDto;
import com.kowalchuk.quoteservice.api.model.QuoteStatusDto;
import com.kowalchuk.quoteservice.submission.Money;

class QuoteMapperTest {

	private static final Instant CREATED_AT = Instant.parse("2026-10-23T14:05:13Z");

	private static final Instant EXPIRES_AT = CREATED_AT.plus(Duration.ofDays(30));

	@Test
	void statusFollowsTheInjectedClock() {
		Quote quote = QuoteFixtures.quotedQuote(CREATED_AT, EXPIRES_AT);

		assertThat(mapperAt(EXPIRES_AT.minusNanos(1_000)).toDto(quote).getStatus()).isEqualTo(QuoteStatusDto.QUOTED);
		assertThat(mapperAt(EXPIRES_AT).toDto(quote).getStatus()).isEqualTo(QuoteStatusDto.EXPIRED);
	}

	@Test
	void expiredQuoteKeepsItsPremiumAndFactors() {
		QuoteDto dto = mapperAt(EXPIRES_AT.plusSeconds(1)).toDto(QuoteFixtures.quotedQuote(CREATED_AT, EXPIRES_AT));

		assertThat(dto.getStatus()).isEqualTo(QuoteStatusDto.EXPIRED);
		assertThat(dto.getPremium().getAmount()).isEqualTo("2520.00");
		assertThat(dto.getRatingFactors()).hasSize(1);
	}

	@Test
	void formatsMoneyWithTwoDecimalsAndFactorsWithFour() {
		QuoteDto dto = mapperAt(CREATED_AT).toDto(QuoteFixtures.quotedQuote(CREATED_AT, EXPIRES_AT));

		assertThat(dto.getId()).isEqualTo("qte_TEST");
		assertThat(dto.getSubmissionId()).isEqualTo("sub_TEST");
		assertThat(dto.getPremium().getAmount()).isEqualTo("2520.00");
		assertThat(dto.getPremium().getCurrency()).isEqualTo("USD");
		assertThat(dto.getRatingFactors().get(0).getFactor()).isEqualTo("2.1000");
		assertThat(dto.getRatingFactors().get(0).getInputValue()).isEqualTo("722511");
		assertThat(dto.getStatusReasons()).isEmpty();
		assertThat(dto.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-10-23T14:05:13Z"));
		assertThat(dto.getExpiresAt()).isEqualTo(OffsetDateTime.parse("2026-11-22T14:05:13Z"));
		assertThat(dto.getExpiresAt().getOffset()).isEqualTo(ZoneOffset.UTC);
	}

	@Test
	void unpricedQuoteHasNoPremiumOrExpiryButHasReasons() {
		QuoteDto dto = mapperAt(CREATED_AT).toDto(QuoteFixtures.unpricedQuote(QuoteFixtures.referred(), CREATED_AT));

		assertThat(dto.getStatus()).isEqualTo(QuoteStatusDto.REFERRED);
		assertThat(dto.getPremium()).isNull();
		assertThat(dto.getExpiresAt()).isNull();
		assertThat(dto.getRatingFactors()).isEmpty();
		assertThat(dto.getStatusReasons()).singleElement()
			.satisfies(reason -> assertThat(reason.getCode()).isEqualTo("REVENUE_ABOVE_AUTO_LIMIT"));
	}

	/** Values Quote.create would reject are forced in, to show the mapper fails rather than rounds. */
	@Test
	void neverRounds() {
		Quote extraCent = QuoteFixtures.quotedQuote(CREATED_AT, EXPIRES_AT);
		ReflectionTestUtils.setField(extraCent, "premium", new Money(new BigDecimal("2520.005"), "USD"));
		assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> mapperAt(CREATED_AT).toDto(extraCent));

		Quote preciseFactor = QuoteFixtures.quotedQuote(CREATED_AT, EXPIRES_AT);
		ReflectionTestUtils.setField(preciseFactor, "ratingFactors", new ArrayList<>(List.of(
				new RatingFactorSnapshot("CLASS", "Class of business", "722511", new BigDecimal("2.10001")))));
		assertThatExceptionOfType(ArithmeticException.class)
			.isThrownBy(() -> mapperAt(CREATED_AT).toDto(preciseFactor));
	}

	private static QuoteMapper mapperAt(Instant now) {
		return new QuoteMapper(Clock.fixed(now, ZoneOffset.UTC));
	}

}
