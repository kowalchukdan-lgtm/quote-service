package com.kowalchuk.quoteservice.extraction;

/**
 * Why a field needs a reviewer's attention. Flags never block extraction; they tell the
 * underwriter where to look before confirming the draft.
 */
public enum FlagReason {

	/** The document doesn't give a value. The field is null; it was not guessed. */
	MISSING,

	/** The document gives an estimate ("roughly", "about", "approx."). */
	APPROXIMATE,

	/** The document is unclear or contradicts itself, or the value was inferred rather than stated. */
	AMBIGUOUS,

	/** The model returned a value that breaks the contract's format, so it was discarded. */
	INVALID,

	/** The document contains text addressed to an automated system. It changed nothing, but a human should know. */
	SUSPECTED_INSTRUCTION

}
