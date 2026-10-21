package com.multi.vidulum.bank_data_ai_categorization.app;

import com.multi.vidulum.bank_data_ai_categorization.api.PatternMatches;
import com.multi.vidulum.bank_data_ai_categorization.api.PatternSource;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionToMatch;
import com.multi.vidulum.bank_data_ai_categorization.domain.PatternMapping;
import com.multi.vidulum.bank_data_ai_categorization.domain.PatternMappingRepository;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.common.CashFlowId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rule that used to live in two copies, now asserted in one place.
 *
 * <p>Before the split, staging matched with {@code name.toUpperCase().contains(pattern)} while the
 * patterns were produced by {@link TransactionNameNormalizer} - two rules for one relationship, and
 * no test anywhere compared them. These cases are the comparison.
 */
class LearnedPatternMatcherTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");

    private final PatternMappingRepository patterns = mock(PatternMappingRepository.class);
    private final LearnedPatternMatcher matcher =
            new LearnedPatternMatcher(patterns, new TransactionNameNormalizer());

    /**
     * The case {@code contains} got right and normalize-and-compare would lose: the normalizer only
     * recognises a brand at the start of a name, and a bank writes the acronym last.
     */
    @Test
    void aPatternIsFoundWhereverItAppearsAsAWholeWord() {
        remembered(userPattern("ZUS", Type.OUTFLOW, "Składki społeczne", 0.95));

        PatternMatches matches = matcher.match(CASH_FLOW_ID, List.of(
                new TransactionToMatch("T1", "ZAKŁAD UBEZPIECZEŃ SPOŁECZNYCH ZUS", Type.OUTFLOW),
                new TransactionToMatch("T2", "ZUS SKŁADKI 01/2026 NR 123456789", Type.OUTFLOW)));

        assertThat(matches.forTransaction("T1")).isPresent();
        assertThat(matches.forTransaction("T2")).isPresent();
        assertThat(matches.forTransaction("T1").orElseThrow().category()).isEqualTo("Składki społeczne");
    }

    /**
     * <b>The defect this split exists to remove.</b> Under the old rule a learned {@code MOL} made
     * {@code DEMOLKA SERWIS} fuel, silently - and the seed list ships {@code BP}, {@code CCC},
     * {@code DOZ} and {@code AXA}, so three letters inside a longer word is not a rare shape.
     */
    @Test
    void aShortPatternDoesNotSwallowALongerUnrelatedWord() {
        remembered(userPattern("MOL", Type.OUTFLOW, "Paliwo", 0.93));

        PatternMatches matches = matcher.match(CASH_FLOW_ID, List.of(
                new TransactionToMatch("T1", "DEMOLKA SERWIS SP ZOO", Type.OUTFLOW),
                new TransactionToMatch("T2", "MOL STACJA KRAKOW", Type.OUTFLOW)));

        assertThat(matches.forTransaction("T1"))
                .as("MOL is inside DEMOLKA the way ART is inside PARTNER")
                .isEmpty();
        assertThat(matches.forTransaction("T2"))
                .as("the same three letters as their own word are the pattern")
                .isPresent();
    }

    @Test
    void aPatternLearnedOnOneSideDoesNotMatchTheOther() {
        remembered(userPattern("PRZELEW", Type.INFLOW, "Wpływy", 0.9));

        PatternMatches matches = matcher.match(CASH_FLOW_ID, List.of(
                new TransactionToMatch("T1", "PRZELEW WYCHODZĄCY", Type.OUTFLOW)));

        assertThat(matches.isEmpty())
                .as("an outflow must not inherit what an inflow taught")
                .isTrue();
    }

    @Test
    void theMostConfidentPatternWinsWhenTwoRecognizeTheName() {
        remembered(
                userPattern("BIEDRONKA", Type.OUTFLOW, "Zakupy ogólne", 0.80),
                userPattern("BIEDRONKA", Type.OUTFLOW, "Jedzenie", 0.99));

        PatternMatches matches = matcher.match(CASH_FLOW_ID, List.of(
                new TransactionToMatch("T1", "BIEDRONKA 1234 WARSZAWA", Type.OUTFLOW)));

        assertThat(matches.forTransaction("T1").orElseThrow().category())
                .as("behaviour kept from the handlers this replaced")
                .isEqualTo("Jedzenie");
    }

    /**
     * Boundaries are decided with {@link Character#isLetterOrDigit}, not a {@code \b} regex, because
     * these names are full of Ł, Ż and Ę - and {@code \b} keys on ASCII.
     */
    @Test
    void polishLettersStillCountAsPartOfAWord() {
        remembered(userPattern("ŻABKA", Type.OUTFLOW, "Sklepy", 0.98));

        PatternMatches matches = matcher.match(CASH_FLOW_ID, List.of(
                new TransactionToMatch("T1", "ŻABKA Z7733 KRAKÓW", Type.OUTFLOW),
                new TransactionToMatch("T2", "ŻABKAMI HANDEL", Type.OUTFLOW)));

        assertThat(matches.forTransaction("T1")).isPresent();
        assertThat(matches.forTransaction("T2"))
                .as("ŻABKAMI is a different word, and Ż must not break the boundary check")
                .isEmpty();
    }

    @Test
    void aRecognizedPatternHasItsUsageRecorded() {
        PatternMapping zus = userPattern("ZUS", Type.OUTFLOW, "Składki społeczne", 0.95);
        remembered(zus);

        matcher.match(CASH_FLOW_ID, List.of(new TransactionToMatch("T1", "ZUS SKŁADKI", Type.OUTFLOW)));

        verify(patterns).recordUsage(zus.id());
    }

    @Test
    void aPatternThatRecognizedNothingIsNotTouched() {
        PatternMapping mol = userPattern("MOL", Type.OUTFLOW, "Paliwo", 0.93);
        remembered(mol);

        matcher.match(CASH_FLOW_ID, List.of(new TransactionToMatch("T1", "DEMOLKA SERWIS", Type.OUTFLOW)));

        verify(patterns, never()).recordUsage(mol.id());
    }

    @Test
    void aCashFlowThatTaughtNothingIsNotAskedAnythingElse() {
        when(patterns.findAllByCashFlowId(CASH_FLOW_ID.id())).thenReturn(List.of());

        PatternMatches matches = matcher.match(CASH_FLOW_ID, List.of(
                new TransactionToMatch("T1", "BIEDRONKA 1234", Type.OUTFLOW)));

        assertThat(matches.isEmpty()).isTrue();
    }

    @Test
    void anEmptyBatchDoesNotEvenReadTheMemory() {
        matcher.match(CASH_FLOW_ID, List.of());

        verifyNoInteractions(patterns);
    }

    private void remembered(PatternMapping... remembered) {
        when(patterns.findAllByCashFlowId(CASH_FLOW_ID.id())).thenReturn(List.of(remembered));
    }

    private static PatternMapping userPattern(String pattern, Type type, String category, double confidence) {
        return PatternMapping.createUser(pattern, category, null, type, null, CASH_FLOW_ID.id(), confidence);
    }
}
