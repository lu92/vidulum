package com.multi.vidulum;

import com.multi.vidulum.shared.ddd.event.IntegrationEvent;
import com.multi.vidulum.shared.module.ModuleApi;
import com.multi.vidulum.shared.module.ModuleEventHandler;
import com.multi.vidulum.shared.module.ModuleSpi;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The module boundaries, asserted instead of remembered.
 *
 * <p>This test lives in {@code vidulum-app} because it is the only module that sees every
 * implementation on its classpath — which is itself one of the rules being checked: nothing else
 * depends on an implementation at compile scope.
 *
 * <p><b>On the exemption lists.</b> Two rules below carry one. That is deliberate and is what makes
 * this test useful today rather than after the remaining steps of the module split: the rule states
 * the target, the list states how far we are from it, and the list is only allowed to shrink. A rule
 * that cannot be stated until it already passes everywhere is a rule that never gets written.
 */
class ModuleBoundariesArchTest {

    private static JavaClasses classesUnderTest;

    @BeforeAll
    static void importProductionClasses() {
        // Jars are included on purpose: every other module arrives here as one, and they are the
        // subject of this test. Only com.multi.vidulum is imported, so no third-party jar is read.
        classesUnderTest = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.multi.vidulum");
    }

    // ------------------------------------------------------------------ contracts

    @Test
    void aModuleApiIsAnInterfaceInAnApiPackage() {
        // assignableTo, not implement(): a contract *extends* the marker, and implement() only
        // matches classes. The marker itself is assignable to itself, hence the exclusion.
        ArchRule rule = classes().that().areAssignableTo(ModuleApi.class).and().areInterfaces()
                .and().doNotHaveFullyQualifiedName(ModuleApi.class.getName())
                .should().resideInAPackage("..api..")
                .because("a contract belongs in the module's *-api artifact, where consumers compile against it");

        rule.check(classesUnderTest);
    }

    @Test
    void theImplementationOfAModuleApiIsNotPublic() {
        ArchRule rule = classes().that().implement(ModuleApi.class).and().areNotInterfaces()
                .and().haveNameNotMatching(PUBLIC_FOR_A_TEST_IN_ANOTHER_ARTIFACT)
                .should().notBePublic()
                .because("nobody outside the owning module names the implementation - they inject the contract");

        rule.check(classesUnderTest);
    }

    @Test
    void aContractDoesNotDependOnSpring() {
        ArchRule rule = noClasses().that().areAssignableTo(ModuleApi.class).and().areInterfaces()
                .and().doNotHaveFullyQualifiedName(ModuleApi.class.getName())
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("a contract carries no framework: it is compiled against by modules that may wire it differently");

        rule.check(classesUnderTest);
    }

    @Test
    void anSpiIsImplementedOutsideTheModuleThatDeclaresIt() {
        ArchRule rule = classes().that().implement(ModuleSpi.class).and().areNotInterfaces()
                .and().haveNameNotMatching(PROVIDERS_STILL_NEXT_TO_THE_SOCKET)
                .should().resideOutsideOfPackage("..quotation.app..")
                .because("a socket exists so another module can plug in; an implementation next to the socket "
                        + "means the module is talking to itself through its own extension point");

        rule.check(classesUnderTest);
    }

    // ------------------------------------------------------------------ events

    @Test
    void whatCrossesKafkaIsDeclaredThroughAHandlerInterface() {
        ArchRule rule = classes()
                .that(describe("declare a @KafkaListener method", javaClass -> javaClass.getMethods().stream()
                        .anyMatch(method -> method.isAnnotatedWith(KafkaListener.class))))
                .and().haveNameNotMatching(LISTENERS_STILL_ON_RAW_STRINGS)
                .should().implement(ModuleEventHandler.class)
                .because("the topic and the container factory belong to the producer, and 'who listens to X' "
                        + "should be answerable from the type hierarchy rather than from a grep for a string");

        rule.check(classesUnderTest);
    }

    @Test
    void aKafkaListenerIsNotPublicApi() {
        ArchRule rule = classes().that().implement(ModuleEventHandler.class).and().areNotInterfaces()
                .and().haveNameNotMatching(PUBLIC_FOR_A_TEST_IN_ANOTHER_ARTIFACT)
                .should().notBePublic()
                .because("what a module listens to is its own business; the interface it implements is the public part")
                // The one handler that exists is on the exemption list above, so there is nothing to
                // check yet. The rule guards the next one.
                .allowEmptyShould(true);

        rule.check(classesUnderTest);
    }

    @Test
    void anIntegrationEventIsARecord() {
        ArchRule rule = classes().that().implement(IntegrationEvent.class)
                .and().haveNameNotMatching(EVENTS_STILL_CLASSES)
                .should().beAssignableTo(Record.class)
                .because("a serialised payload is a contract towards consumers that cannot be found and changed "
                        + "in one commit - step 05c converts the remaining ones")
                // Every payload that exists today is on the list below, so there is nothing left to
                // check - the rule is here to catch the next one written, and to fail the moment a
                // name comes off the list without becoming a record.
                .allowEmptyShould(true);

        rule.check(classesUnderTest);
    }

    // ------------------------------------------------------------------ module internals

    @Test
    void nobodyReachesIntoAnotherModulesInternals() {
        assertSealed("portfolio", "com.multi.vidulum.portfolio..", "com.multi.vidulum.portfolio_spec..");
        assertSealed("trading", "com.multi.vidulum.trading..");
        assertSealed("pnl", "com.multi.vidulum.pnl..");
        assertSealed("risk_management", "com.multi.vidulum.risk_management..");
        assertSealed("quotation", "com.multi.vidulum.quotation..");
        assertSealed("exchange_connection", "com.multi.vidulum.exchange_connection..");
        assertSealed("okx", "com.multi.vidulum.okx..");
        assertSealed("recurring_rules", "com.multi.vidulum.recurring_rules..");

        // bank_data_ingestion is its own artifact now, so it gets its own seal. It needed none of
        // cashflow's internals to get there: 75 imports, all through vidulum-cashflow-api. The
        // enum that did block it - TransactionClassification - moved to
        // vidulum-bank-data-adapter-api, which is why the adapter is sealed here too.
        assertSealed("bank_data_ingestion", "com.multi.vidulum.bank_data_ingestion..");
        assertSealed("bank_data_adapter", "com.multi.vidulum.bank_data_adapter..");

        assertSealed("user_financial_profile", "com.multi.vidulum.user_financial_profile..");

        // cashflow carries its exemptions as extra "own" packages rather than as a name list,
        // because that is what they are: three subdomains inside the same artifact that still read
        // the aggregate directly. Everything outside this set - portfolio, trading, pnl, the
        // bootstrap - is already forbidden, which is the half worth freezing today.
        //
        // One exemption left, and it is the one that is meant to stay for now:
        // cashflow_forecast_processor is cashflow's own read model, lives in the same artifact by
        // design, and projects from 36 imports of CashFlowEvent. The others left the list by
        // leaving the artifact - bank_data_ingestion in step 15 (down to cashflow.api only), and
        // user_financial_profile in step 25, which had been borrowing BankAccountNumber and
        // deserialising CashFlowEvent and now declares both itself. The exemptions here are a
        // measurement, not a permanent allowance.
        assertSealed("cashflow",
                "com.multi.vidulum.cashflow..",
                "com.multi.vidulum.cashflow_forecast_processor..");
    }

    /**
     * Everything a module keeps to itself - {@code app}, {@code domain}, {@code infrastructure} -
     * is reachable only from inside that module. The {@code api} package is the hole in the wall.
     */
    private void assertSealed(String module, String... ownPackages) {
        String[] internals = {
                "com.multi.vidulum." + module + ".app..",
                "com.multi.vidulum." + module + ".domain..",
                "com.multi.vidulum." + module + ".infrastructure..",
        };

        ArchRule rule = noClasses().that().resideOutsideOfPackages(ownPackages)
                .should().accessClassesThat().resideInAnyPackage(internals)
                .because(module + " answers through its contract; its handlers, aggregate and storage are private to it");

        rule.check(classesUnderTest);
    }

    // ------------------------------------------------------------------ exemptions, both shrinking

    /**
     * Six of the seven listeners still name their topic and factory as strings. Each one that moves
     * to a handler interface comes off this list; the list reaching empty is what finishes the rule
     * above. {@code user_created} and {@code cash_flow} are blocked on their producers having no
     * contract at all - steps 05b and 05c.
     */
    private static final String LISTENERS_STILL_ON_RAW_STRINGS = ".*(QuotationService"
            + "|UserCreatedEventListener"
            + "|TradeCapturedEventListener"
            + "|CashFlowEventListener"
            + "|UserFinancialProfileCashFlowListener"
            + "|UserFinancialProfileUserCreatedListener)";

    /**
     * Three implementations are public only because a test in another artifact constructs them:
     * {@code FxValuationRatesTest} in vidulum-pnl builds a real {@code FxRates},
     * {@code DenominationChainComponentTest} and {@code OkxQuotationComponentTest} build a real
     * {@code QuotationService}, and {@code PreciousMetalsLifecycleComponentTest} wires
     * {@code TradeConfirmedToPortfolio} directly. Each is a deliberate cross-artifact test
     * dependency, and each would need its test rewritten before the class can close up.
     *
     * <p>{@code PortfolioAccess} was on this list until this step: its only cross-artifact mentions
     * turned out to be javadoc, so it is package-private now.
     */
    private static final String PUBLIC_FOR_A_TEST_IN_ANOTHER_ARTIFACT =
            ".*(FxRates|QuotationService|TradeConfirmedToPortfolio)";

    /**
     * Binance, PM, Degiro and Exante providers live in {@code quotation.app}, next to the socket
     * they implement, while OKX has its own module. Recorded as open since step 04: they will move
     * out when a second venue gets an adapter of its own.
     */
    private static final String PROVIDERS_STILL_NEXT_TO_THE_SOCKET =
            ".*(Binance|PM|Degiro|Exante)BrokerQuotationProvider";

    /** The seven payloads written before the decision that a serialised payload is a record (05c). */
    private static final String EVENTS_STILL_CLASSES = ".*(TradeConfirmedEvent"
            + "|TradeCapturedEvent"
            + "|PriceChangedEvent"
            + "|UserCreatedEvent"
            + "|CashFlowUnifiedEvent"
            + "|UserFinancialProfileUnifiedEvent"
            + "|BankDataIngestionUnifiedEvent)";
}
