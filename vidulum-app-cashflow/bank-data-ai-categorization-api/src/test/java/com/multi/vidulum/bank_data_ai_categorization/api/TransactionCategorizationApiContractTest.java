package com.multi.vidulum.bank_data_ai_categorization.api;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Money;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every implementation of {@link TransactionCategorizationApi} must do, whoever wrote it.
 *
 * <p><b>Why this exists.</b> When the engine was lifted into its own module (F66), the review of
 * that work found the swap was structural and not behavioural: a replacement knew which four
 * methods to write and had <b>nothing to run</b> to find out whether it wrote them the way the
 * consumer needs. The promises below were obligations in a javadoc; here they are a test an
 * implementation runs on itself.
 *
 * <p><b>What is asserted and what is deliberately not.</b> Only promises that hold regardless of how
 * a transaction gets classified: the matching rule, the isolation between cash flows and between
 * INFLOW and OUTFLOW, learning that corrects rather than duplicates, and forgetting scoped to one
 * owner. Nothing here demands a language model, a cache, tokens or a cost - a static classifier
 * must be able to pass this class unchanged, which is the whole point of the contract being named
 * after the capability rather than the supplier.
 *
 * <p>Implementations extend this and return a working instance from {@link #categorizationApi()}.
 * Each test gets a cash flow id of its own, so an implementation that keeps state between tests does
 * not need to reset anything.
 */
public abstract class TransactionCategorizationApiContractTest {

    /**
     * @return the implementation under test, ready to use
     */
    protected abstract TransactionCategorizationApi categorizationApi();

    /**
     * @return a cash flow nobody else in this run is using
     */
    protected abstract CashFlowId freshCashFlow();

    // ---------- matchLearned ----------

    /**
     * The rule that two staging handlers used to own a copy of each: a pattern matches where it
     * appears as a <b>whole word</b>, wherever in the name that is.
     *
     * <p>Banks write the acronym last often enough to matter -
     * {@code ZAKŁAD UBEZPIECZEŃ SPOŁECZNYCH ZUS} - so matching only a prefix is not enough.
     */
    @Test
    void aLearnedPatternIsRecognizedWhereverItAppearsAsAWholeWord() {
        CashFlowId cashFlow = freshCashFlow();
        categorizationApi().learn(cashFlow, List.of(
                new PatternToLearn("ZUS", Type.OUTFLOW, "Składki", null, 0.95, PatternSource.USER)));

        PatternMatches matches = categorizationApi().matchLearned(cashFlow, List.of(
                new TransactionToMatch("T1", "ZUS SKŁADKI 01/2026", Type.OUTFLOW),
                new TransactionToMatch("T2", "ZAKŁAD UBEZPIECZEŃ SPOŁECZNYCH ZUS", Type.OUTFLOW)));

        assertThat(matches.forTransaction("T1")).isPresent();
        assertThat(matches.forTransaction("T2"))
                .as("the acronym at the end of the name is still the pattern")
                .isPresent();
        assertThat(matches.forTransaction("T1").orElseThrow().category()).isEqualTo("Składki");
    }

    /**
     * The other half of that rule, and the defect it was written for: a short pattern must not be
     * found inside a longer word. The seeded vocabulary has {@code BP}, {@code CCC}, {@code DOZ},
     * {@code AXA} and {@code MOL}, so this is not a corner case.
     */
    @Test
    void aShortPatternIsNotFoundInsideALongerWord() {
        CashFlowId cashFlow = freshCashFlow();
        categorizationApi().learn(cashFlow, List.of(
                new PatternToLearn("MOL", Type.OUTFLOW, "Paliwo", "Transport", 0.93, PatternSource.USER)));

        PatternMatches matches = categorizationApi().matchLearned(cashFlow, List.of(
                new TransactionToMatch("T1", "DEMOLKA SERWIS SP ZOO", Type.OUTFLOW),
                new TransactionToMatch("T2", "MOL STACJA KRAKOW", Type.OUTFLOW)));

        assertThat(matches.forTransaction("T1"))
                .as("MOL is inside DEMOLKA the way ART is inside PARTNER")
                .isEmpty();
        assertThat(matches.forTransaction("T2")).isPresent();
    }

    @Test
    void aPatternLearnedOnOneSideDoesNotMatchTheOther() {
        CashFlowId cashFlow = freshCashFlow();
        categorizationApi().learn(cashFlow, List.of(
                new PatternToLearn("PRZELEW", Type.INFLOW, "Wpływy", null, 0.9, PatternSource.USER)));

        PatternMatches matches = categorizationApi().matchLearned(cashFlow, List.of(
                new TransactionToMatch("T1", "PRZELEW WYCHODZĄCY", Type.OUTFLOW)));

        assertThat(matches.isEmpty())
                .as("an outflow must not inherit what an inflow taught")
                .isTrue();
    }

    @Test
    void whatOneCashFlowLearnedIsNotOfferedToAnother() {
        CashFlowId taught = freshCashFlow();
        CashFlowId other = freshCashFlow();
        categorizationApi().learn(taught, List.of(
                new PatternToLearn("BIEDRONKA", Type.OUTFLOW, "Jedzenie", null, 0.99, PatternSource.USER)));

        PatternMatches matches = categorizationApi().matchLearned(other, List.of(
                new TransactionToMatch("T1", "BIEDRONKA 1234 WARSZAWA", Type.OUTFLOW)));

        assertThat(matches.isEmpty())
                .as("patterns are one owner's memory, not a shared rulebook")
                .isTrue();
    }

    @Test
    void aTransactionNothingRecognizesIsSimplyAbsentFromTheAnswer() {
        CashFlowId cashFlow = freshCashFlow();

        PatternMatches matches = categorizationApi().matchLearned(cashFlow, List.of(
                new TransactionToMatch("T1", "NIEZNANY SKLEP", Type.OUTFLOW)));

        assertThat(matches.forTransaction("T1"))
                .as("an absent id means 'carry on to the ordinary path', not an error")
                .isEmpty();
    }

    @Test
    void anEmptyBatchIsAnsweredWithNoMatches() {
        assertThat(categorizationApi().matchLearned(freshCashFlow(), List.of()).isEmpty()).isTrue();
    }

    // ---------- learn ----------

    @Test
    void learningAPatternTwiceCorrectsItInsteadOfStoringItAgain() {
        CashFlowId cashFlow = freshCashFlow();
        int first = categorizationApi().learn(cashFlow, List.of(
                new PatternToLearn("NETFLIX", Type.OUTFLOW, "Rozrywka", null, 0.8, PatternSource.AI)));

        int second = categorizationApi().learn(cashFlow, List.of(
                new PatternToLearn("NETFLIX", Type.OUTFLOW, "Subskrypcje", "Rozrywka", 0.97, PatternSource.USER)));

        assertThat(first).as("the first acceptance is something new").isEqualTo(1);
        assertThat(second).as("the second corrects what was known, it does not add to it").isZero();

        PatternMatches matches = categorizationApi().matchLearned(cashFlow, List.of(
                new TransactionToMatch("T1", "NETFLIX.COM AMSTERDAM", Type.OUTFLOW)));
        assertThat(matches.forTransaction("T1").orElseThrow().category())
                .as("the latest acceptance is what the owner meant")
                .isEqualTo("Subskrypcje");
    }

    @Test
    void learningNothingChangesNothing() {
        assertThat(categorizationApi().learn(freshCashFlow(), List.of())).isZero();
    }

    // ---------- forget ----------

    @Test
    void forgettingLeavesNothingToMatchForThatCashFlowOnly() {
        CashFlowId forgotten = freshCashFlow();
        CashFlowId kept = freshCashFlow();
        categorizationApi().learn(forgotten, List.of(
                new PatternToLearn("LIDL", Type.OUTFLOW, "Jedzenie", null, 0.99, PatternSource.USER)));
        categorizationApi().learn(kept, List.of(
                new PatternToLearn("LIDL", Type.OUTFLOW, "Jedzenie", null, 0.99, PatternSource.USER)));

        categorizationApi().forget(forgotten);

        TransactionToMatch lidl = new TransactionToMatch("T1", "LIDL 998 KRAKOW", Type.OUTFLOW);
        assertThat(categorizationApi().matchLearned(forgotten, List.of(lidl)).isEmpty())
                .as("an import being rolled back takes the acceptances it produced with it")
                .isTrue();
        assertThat(categorizationApi().matchLearned(kept, List.of(lidl)).forTransaction("T1"))
                .as("another owner's memory is not collateral")
                .isPresent();
    }

    // ---------- suggestFor ----------

    /**
     * The only two things {@code suggestFor} can promise without assuming how classification
     * happens: the caller's correlation id comes back, and a batch with nothing in it costs nothing
     * and suggests nothing.
     */
    @Test
    void anEmptyBatchIsAnsweredWithoutSuggestions() {
        AiCategorizationResult result = categorizationApi().suggestFor(new CategorizationRequest(
                "SS-CONTRACT-1", freshCashFlow(), "pl", List.of(), CategoryStructure.empty()));

        assertThat(result.sessionId())
                .as("the session id is opaque and echoed, so the caller can correlate")
                .isEqualTo("SS-CONTRACT-1");
        assertThat(result.patternSuggestions()).isEmpty();
    }

    @Test
    void theSessionIdIsEchoedForABatchThatHasWorkInIt() {
        AiCategorizationResult result = categorizationApi().suggestFor(new CategorizationRequest(
                "SS-CONTRACT-2", freshCashFlow(), "pl",
                List.of(new TransactionToCategorize("T1", "OPLATA ZA PROWADZENIE RACHUNKU", null,
                        Money.of(-9.00, "PLN"), Type.OUTFLOW, "Oplaty", null, null,
                        "BANK_FEE", true, null)),
                CategoryStructure.empty()));

        assertThat(result.sessionId()).isEqualTo("SS-CONTRACT-2");
    }
}
