package com.kowalchuk.quoteservice.intake;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface ExtractionRepository extends JpaRepository<Extraction, String> {

	/**
	 * Loads the row with a write lock (SELECT ... FOR UPDATE), so two reviewers confirming the
	 * same draft at once can't both create a submission: the second waits, then sees CONFIRMED.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Extraction> findForUpdateById(String id);

}
