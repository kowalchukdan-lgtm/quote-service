package com.kowalchuk.quoteservice.submission;

import java.time.LocalDate;

/**
 * The input to {@link SubmissionService#create}: a contract-valid request, translated out of
 * the generated API types so the service does not depend on them.
 */
public record NewSubmission(
		String businessName,
		Address address,
		String naicsCode,
		Money annualRevenue,
		Money perOccurrenceLimit,
		Money aggregateLimit,
		LocalDate effectiveDate) {
}
