package com.kowalchuk.quoteservice.extraction;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Keeps the extraction logic plain Java and provider-neutral. Only {@code extraction.azure}
 * (the model adapter) may use Spring or the Azure SDK, so changing the model provider means
 * replacing one adapter, and the core stays testable with a fake model.
 * <p>
 * It also keeps extraction away from the submission and quote packages: a draft can't create a
 * submission or a quote by itself.
 */
@AnalyzeClasses(packages = "com.kowalchuk.quoteservice.extraction")
class ExtractionArchitectureTest {

	@ArchTest
	static final ArchRule extractionCoreIsFrameworkFree = noClasses().that()
		.resideInAPackage("com.kowalchuk.quoteservice.extraction")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("org.springframework..", "com.azure..", "jakarta.persistence..", "org.hibernate..",
				"java.sql..", "javax.sql..")
		.orShould()
		.dependOnClassesThat()
		.resideInAnyPackage("com.kowalchuk.quoteservice.extraction.azure..")
		.because("the extraction core must stay plain Java and must not depend on a particular model provider");

	@ArchTest
	static final ArchRule extractionCannotSubmitOrQuote = noClasses().that()
		.resideInAPackage("com.kowalchuk.quoteservice.extraction..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.kowalchuk.quoteservice.submission..", "com.kowalchuk.quoteservice.quote..",
				"com.kowalchuk.quoteservice.rating..", "com.kowalchuk.quoteservice.reference..",
				"com.kowalchuk.quoteservice.api..")
		.because("extraction only drafts; a person confirms the draft, and the normal submission rules apply");

}
