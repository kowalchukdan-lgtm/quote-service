package com.kowalchuk.quoteservice.web;

import java.util.List;

/**
 * A request that matches the contract but breaks one or more business rules. Rendered as
 * 422 {@code business-rule-violation} with every violation listed.
 */
public class BusinessRuleViolationException extends RuntimeException {

	private final List<FieldViolation> violations;

	public BusinessRuleViolationException(List<FieldViolation> violations) {
		super(violations.size() + " business rule violation(s)");
		this.violations = List.copyOf(violations);
	}

	public List<FieldViolation> getViolations() {
		return violations;
	}

}
