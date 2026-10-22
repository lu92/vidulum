package com.multi.vidulum.bank_data_ingestion.canonical;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The canonical transaction row may be used by every section of this module and may use none of
 * them.
 *
 * <p><b>Why this is a test and not a convention.</b> The four types here are what a bank statement
 * becomes once it stops being a file: {@code intake} produces them, {@code staging} stores them, the
 * classification section reads them. The moment one of them reaches back - for a staging session, a
 * category mapping, an import job - the vocabulary stops being a vocabulary and becomes a sixth
 * participant in the module's wiring, and nothing about that failure is visible in a review: the
 * code compiles, the tests pass, and the next person to split this module finds the sections welded
 * together.
 *
 * <p>This is the first of the three rules from
 * {@code docs/features-backlog/2026-10-22-bank-data-ingestion-sections.md} (F67). The other two -
 * that {@code intake} and {@code mappings} are leaves, and that <b>nothing</b> depends on
 * {@code classification} - need the sections to exist as packages first, and are deliberately not
 * asserted here yet rather than asserted vacuously.
 */
class CanonicalRowIsSelfContainedTest {

    private static final String MODULE = "com.multi.vidulum.bank_data_ingestion";

    private final JavaClasses moduleClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(MODULE);

    @Test
    void theCanonicalRowDoesNotDependOnTheRestOfTheModule() {
        ArchRule rule = noClasses().that().resideInAPackage("..bank_data_ingestion.canonical..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..bank_data_ingestion.app..",
                        "..bank_data_ingestion.domain..",
                        "..bank_data_ingestion.infrastructure..")
                .because("intake produces the canonical row, staging stores it and classification "
                        + "reads it - a vocabulary that knows its users is not a vocabulary");

        rule.check(moduleClasses);
    }

    /**
     * And it does not depend on the categorization module either. The engine takes its own
     * {@code TransactionToCategorize} and the classification word as text, which is what keeps that
     * contract swappable (F66).
     */
    @Test
    void theCanonicalRowDoesNotKnowWhoClassifiesTransactions() {
        ArchRule rule = noClasses().that().resideInAPackage("..bank_data_ingestion.canonical..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..bank_data_ai_categorization..")
                .because("the row is what a statement becomes, not what anyone decides about it");

        rule.check(moduleClasses);
    }

    @Test
    void theCanonicalPackageHasNoCyclesWithinIt() {
        ArchRule rule = slices().matching("..bank_data_ingestion.(*)..")
                .should().beFreeOfCycles()
                .because("a cycle between the vocabulary and a section is the failure this package exists to prevent");

        rule.check(moduleClasses);
    }
}
