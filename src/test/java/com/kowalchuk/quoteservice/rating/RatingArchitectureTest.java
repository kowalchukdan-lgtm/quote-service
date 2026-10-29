package com.kowalchuk.quoteservice.rating;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Keeps the rating engine plain Java, so its tests stay fast and its rules can be read and
 * reused without the framework. Test classes in the package are checked too: a rating test
 * that needs Spring or a database is not a unit test.
 */
@AnalyzeClasses(packages = "com.kowalchuk.quoteservice.rating")
class RatingArchitectureTest {

	@ArchTest
	static final ArchRule ratingIsFrameworkFree = noClasses().that()
		.resideInAPackage("com.kowalchuk.quoteservice.rating..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..", "java.sql..",
				"javax.sql..", "org.postgresql..", "org.flywaydb..", "org.testcontainers..")
		.orShould()
		.dependOnClassesThat()
		.resideInAnyPackage("com.kowalchuk.quoteservice.quote..", "com.kowalchuk.quoteservice.reference..",
				"com.kowalchuk.quoteservice.submission..", "com.kowalchuk.quoteservice.config..",
				"com.kowalchuk.quoteservice.web..")
		.because("the rating engine must stay plain Java: no Spring, JPA or database, "
				+ "and no dependency on the JPA-backed application packages");

}
