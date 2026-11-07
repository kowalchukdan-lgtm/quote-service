package com.kowalchuk.quoteservice.intake;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.kowalchuk.quoteservice.extraction.Confidence;
import com.kowalchuk.quoteservice.extraction.ExtractedAddress;
import com.kowalchuk.quoteservice.extraction.ExtractedSubmission;
import com.kowalchuk.quoteservice.extraction.FieldEvidence;
import com.kowalchuk.quoteservice.extraction.FieldFlag;
import com.kowalchuk.quoteservice.extraction.FlagReason;
import com.kowalchuk.quoteservice.submission.Address;
import com.kowalchuk.quoteservice.submission.Money;
import com.kowalchuk.quoteservice.submission.NaicsClass;
import com.kowalchuk.quoteservice.submission.NewSubmission;
import com.kowalchuk.quoteservice.submission.Submission;

/** Shared test data: the taco stand draft from the golden set, with revenue missing. */
final class ExtractionFixtures {

	static final Instant CREATED_AT = Instant.parse("2026-11-07T17:02:41Z");

	static final String SHA256 = "a".repeat(64);

	private ExtractionFixtures() {
	}

	static ExtractedSubmission tacoStandDraft() {
		return new ExtractedSubmission("Coastal Taco Stand LLC",
				new ExtractedAddress("1402 E 7th Ave", null, "Tampa", "FL", "33605", "US"), "722513", null,
				new BigDecimal("1000000.00"), new BigDecimal("2000000.00"), LocalDate.of(2026, 12, 15),
				List.of(new FieldFlag("annualRevenue", FlagReason.MISSING, "Financials to follow."),
						new FieldFlag(FieldFlag.DOCUMENT, FlagReason.SUSPECTED_INSTRUCTION, null)),
				List.of(new FieldEvidence("naicsCode", "NAICS code 722513", Confidence.HIGH),
						new FieldEvidence("address.city", "Tampa, FL 33605", Confidence.MEDIUM)));
	}

	static Extraction draftExtraction(String id) {
		return Extraction.draft(id, new Extraction.Drafting("taco.pdf", 3011, SHA256,
				new DraftJson().write(tacoStandDraft()), "gpt-5-mini-2025-08-07", 1304, 929, Duration.ofMillis(8400)),
				CREATED_AT);
	}

	/** What a reviewer confirms: the draft with revenue filled in and a current effective date. */
	static NewSubmission reviewedSubmission() {
		Money usd = new Money(new BigDecimal("1000000.00"), "USD");
		return new NewSubmission("Coastal Taco Stand LLC",
				new Address("1402 E 7th Ave", null, "Tampa", "FL", "33605", "US"), "722513",
				new Money(new BigDecimal("650000.00"), "USD"), usd, new Money(new BigDecimal("2000000.00"), "USD"),
				LocalDate.now(ZoneOffset.UTC).plusDays(7));
	}

	static Submission submission(String id) {
		return Submission.create(id, reviewedSubmission(),
				new NaicsClass("722513", "Limited-Service Restaurants", true), CREATED_AT.plusSeconds(300));
	}

}
