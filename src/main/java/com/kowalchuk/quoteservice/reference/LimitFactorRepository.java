package com.kowalchuk.quoteservice.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface LimitFactorRepository extends EffectiveDatedRepository<LimitFactor> {

	// Postgres compares NUMERIC by value, so 1000000 matches 1000000.00.
	default Optional<LimitFactor> findInEffect(BigDecimal perOccurrence, BigDecimal aggregate, LocalDate date) {
		return findInEffect((row, query, cb) -> cb.and(cb.equal(row.get("perOccurrenceLimit"), perOccurrence),
				cb.equal(row.get("aggregateLimit"), aggregate)), date);
	}

}
