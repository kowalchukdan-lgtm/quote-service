package com.kowalchuk.quoteservice.reference;

import java.time.LocalDate;
import java.util.Optional;

public interface RatingStateRepository extends EffectiveDatedRepository<RatingState> {

	default Optional<RatingState> findInEffect(String stateCode, LocalDate date) {
		return findInEffect((row, query, cb) -> cb.equal(row.get("stateCode"), stateCode), date);
	}

}
