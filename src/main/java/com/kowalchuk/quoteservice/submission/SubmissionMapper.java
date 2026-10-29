package com.kowalchuk.quoteservice.submission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import com.kowalchuk.quoteservice.api.model.AddressDto;
import com.kowalchuk.quoteservice.api.model.ClassOfBusinessDetailDto;
import com.kowalchuk.quoteservice.api.model.MoneyDto;
import com.kowalchuk.quoteservice.api.model.RequestedLimitDto;
import com.kowalchuk.quoteservice.api.model.SubmissionDto;
import com.kowalchuk.quoteservice.api.model.SubmissionRequestDto;

/**
 * Translates between the generated API models and the submission types. The only class that
 * knows both, so contract changes and schema changes stay independent.
 */
@Component
public class SubmissionMapper {

	public NewSubmission toNewSubmission(SubmissionRequestDto request) {
		AddressDto address = request.getAddress();
		return new NewSubmission(
				request.getBusinessName(),
				new Address(address.getLine1(), address.getLine2(), address.getCity(), address.getRegion(),
						address.getPostalCode(), address.getCountry()),
				request.getClassOfBusiness().getNaicsCode(),
				toMoney(request.getAnnualRevenue()),
				toMoney(request.getRequestedLimit().getPerOccurrence()),
				toMoney(request.getRequestedLimit().getAggregate()),
				request.getEffectiveDate());
	}

	public SubmissionDto toDto(Submission submission) {
		Address address = submission.getAddress();
		AddressDto addressDto = new AddressDto(address.line1(), address.city(), address.region(),
				address.postalCode(), address.country())
			.line2(address.line2());
		NaicsClass naicsClass = submission.getNaicsClass();
		return new SubmissionDto(
				submission.getId(),
				submission.getBusinessName(),
				addressDto,
				new ClassOfBusinessDetailDto(naicsClass.getCode(), naicsClass.getTitle()),
				toDto(submission.getAnnualRevenue()),
				new RequestedLimitDto(toDto(submission.getPerOccurrenceLimit()), toDto(submission.getAggregateLimit())),
				submission.getEffectiveDate(),
				OffsetDateTime.ofInstant(submission.getCreatedAt(), ZoneOffset.UTC));
	}

	// The contract's pattern guarantees a plain decimal string with at most 2 fractional digits.
	private static Money toMoney(MoneyDto money) {
		return new Money(new BigDecimal(money.getAmount()), money.getCurrency());
	}

	// Always two fractional digits and never exponent notation: "1200000" becomes "1200000.00".
	// UNNECESSARY: amounts already have at most two decimals, so this never rounds.
	private static MoneyDto toDto(Money money) {
		return new MoneyDto(money.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), money.currency());
	}

}
