package com.kowalchuk.quoteservice.reference;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor.SpecificationFluentQuery;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * Read-only access to effective-dated rate rows, with the one shared rule for which row is in
 * effect on a date.
 * <p>
 * Extends {@link Repository} rather than {@code JpaRepository} or
 * {@code JpaSpecificationExecutor}, which expose {@code save}, {@code update} and
 * {@code delete}. The methods below are redeclared from those interfaces, and Spring Data
 * routes them to its standard implementation.
 */
@NoRepositoryBean
public interface EffectiveDatedRepository<T extends EffectiveDatedRow> extends Repository<T, Long> {

	Optional<T> findById(Long id);

	List<T> findAll(Specification<T> spec);

	<S extends T, R> R findBy(Specification<T> spec,
			Function<? super SpecificationFluentQuery<S>, R> queryFunction);

	/**
	 * The row in effect on the given date: among rows matching {@code key}, the one with the
	 * latest effective_from on or before {@code date}; for rows sharing that date, the most
	 * recently loaded one, so a correction row supersedes the row it corrects. Rows are
	 * append-only; rate changes and corrections are both new rows. Callers pass the policy
	 * effective date, not the quote date.
	 */
	default Optional<T> findInEffect(Specification<T> key, LocalDate date) {
		return findBy(key.and(effectiveOnOrBefore(date)),
				query -> query.sortBy(Sort.by(Sort.Direction.DESC, "effectiveFrom")
					.and(Sort.by(Sort.Direction.DESC, "loadedAt")))
					.first());
	}

	static <T extends EffectiveDatedRow> Specification<T> effectiveOnOrBefore(LocalDate date) {
		return (row, query, cb) -> cb.lessThanOrEqualTo(row.get("effectiveFrom"), date);
	}

}
