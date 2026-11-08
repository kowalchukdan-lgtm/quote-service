package com.kowalchuk.quoteservice.archive;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Keeps the intake archive a port with one adapter: only {@code archive.cosmos} knows Cosmos DB,
 * the archive knows nothing of the domain that feeds it, and only configuration picks the adapter.
 */
@AnalyzeClasses(packages = "com.kowalchuk.quoteservice", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchiveArchitectureTest {

	@ArchTest
	static final ArchRule onlyTheAdapterKnowsCosmos = noClasses().that()
		.resideOutsideOfPackage("com.kowalchuk.quoteservice.archive.cosmos..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.azure.cosmos..")
		.because("changing the archive store should mean replacing one adapter");

	@ArchTest
	static final ArchRule theArchiveKnowsNothingOfTheDomain = noClasses().that()
		.resideInAPackage("com.kowalchuk.quoteservice.archive..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.kowalchuk.quoteservice.submission..", "com.kowalchuk.quoteservice.intake..",
				"com.kowalchuk.quoteservice.quote..", "com.kowalchuk.quoteservice.rating..",
				"com.kowalchuk.quoteservice.extraction..", "com.kowalchuk.quoteservice.api..")
		.because("the domain publishes events to the archive, never the other way round");

	@ArchTest
	static final ArchRule onlyConfigurationPicksTheAdapter = noClasses().that()
		.resideOutsideOfPackages("com.kowalchuk.quoteservice.archive.cosmos..", "com.kowalchuk.quoteservice.config..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.kowalchuk.quoteservice.archive.cosmos..")
		.because("code that archives depends on the IntakeArchive port, not on Cosmos");

}
