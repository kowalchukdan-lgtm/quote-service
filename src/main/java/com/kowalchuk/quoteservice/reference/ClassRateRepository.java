package com.kowalchuk.quoteservice.reference;

import java.time.LocalDate;
import java.util.Optional;

public interface ClassRateRepository extends EffectiveDatedRepository<ClassRate> {

	default Optional<ClassRate> findInEffect(String naicsCode, LocalDate date) {
		return findInEffect((row, query, cb) -> cb.equal(row.get("naicsCode"), naicsCode), date);
	}

}
