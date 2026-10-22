package com.multi.vidulum.bank_data_ingestion.canonical;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
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

    /**
     * The second rule of F67, now that {@code intake} exists as a package: bytes become rows and the
     * section that does it knows nothing else.
     *
     * <p>It covers the parser only. {@code upload_csv} is <b>not</b> here on purpose - its result
     * type embeds staging's result and its outcome is a staging session, so it belongs to staging
     * and would make this rule false. That correction came from reading the code, not from the plan.
     */
    @Test
    void intakeKnowsNothingButTheCanonicalRow() {
        ArchRule rule = noClasses().that().resideInAPackage("..bank_data_ingestion.intake..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..bank_data_ingestion.app..",
                        "..bank_data_ingestion.domain..",
                        "..bank_data_ingestion.infrastructure..",
                        "..bank_data_ai_categorization..")
                .because("a parser that knows about staging sessions is no longer a parser");

        rule.check(moduleClasses);
    }

    /**
     * The third rule of F67, and the one the whole exercise is for: <b>nothing depends on
     * classification</b>. While it holds, swapping the classifier cannot reach the sections that
     * parse, stage, map or import.
     *
     * <p>It holds only because the session's classification progress stayed in staging - see this
     * section's {@code package-info}. The controller is allowed: it names every section's commands
     * by definition, and splitting it is a later step of F67.
     */
    @Test
    void nothingDependsOnClassification() {
        ArchRule rule = noClasses().that()
                .resideOutsideOfPackages("..bank_data_ingestion.classification..", "..bank_data_ingestion.app")
                .should().dependOnClassesThat()
                .resideInAPackage("..bank_data_ingestion.classification..")
                .because("a leaf is what makes the classifier replaceable: swap it and intake, "
                        + "staging, mappings and importing do not notice");

        rule.check(moduleClasses);
    }

    /**
     * No cycles between the sections - with one exemption, named rather than hidden.
     *
     * <p><b>The controller and the DTO are excused.</b> One class names every section's commands by
     * definition: {@code BankDataIngestionRestController} builds {@code AiCategorizeCommand} and
     * {@code AcceptAiSuggestionsCommand} while the classification handlers reach back for staging's
     * and mappings' handlers, which is a cycle through 1,195 lines of HTTP mapping. It is a real
     * finding and a known one - splitting that controller along the sections is a later step of F67,
     * and when it happens this exemption goes away rather than being weakened.
     *
     * <p>The exemption is deliberately narrow: only dependencies <b>originating</b> in those two
     * classes are ignored, so a cycle between any two sections themselves still fails here. Like the
     * exemption lists in {@code ModuleBoundariesArchTest}, this one measures a gap and may only
     * shrink.
     */
    @Test
    void theSectionsHaveNoCyclesBetweenThem() {
        ArchRule rule = slices().matching("..bank_data_ingestion.(*)..")
                .should().beFreeOfCycles()
                .ignoreDependency(
                        resideIn("..bank_data_ingestion.app"),
                        DescribedPredicate.alwaysTrue())
                .because("a cycle between two sections is the failure this layout exists to prevent; "
                        + "the one through the shared controller is known and named");

        rule.check(moduleClasses);
    }

    private static DescribedPredicate<JavaClass> resideIn(String packageIdentifier) {
        return JavaClass.Predicates.resideInAPackage(packageIdentifier);
    }
}
