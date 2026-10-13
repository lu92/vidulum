package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio_spec.domain.Difference;
import com.multi.vidulum.portfolio_spec.domain.QuestionKind;
import com.multi.vidulum.portfolio_spec.domain.ResolutionRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The table from §4.2, one test per row (task D4).
 *
 * <p>What these tests really protect is the user's patience: without the rules every difference
 * becomes a question, including an ordinary purchase the exchange already priced. A mechanism
 * that interrogates people about their own trades is one they switch off.
 */
class ResolutionRulesTest {

    private static final Ticker BTC = Ticker.of("BTC");

    @Test
    void shouldSettleAnIncreaseThatTheExchangePriced() {
        Difference difference = ResolutionRules.forIncrease(
                BTC, SubName.traded(), Quantity.of(0.5), Price.of(50_000, "USD"));

        assertThat(difference.needsAnswer()).isFalse();
        assertThat(difference.resolvedCost().provenance()).isEqualTo(Provenance.EXCHANGE_REPORTED);
        assertThat(difference.resolvedCost().quantity()).isEqualTo(Quantity.of(0.5));
        assertThat(difference.resolvedCost().avgPrice()).isEqualTo(Price.of(50_000, "USD"));
    }

    /**
     * Asking "how much did your 500 EUR cost you?" is noise, and noise is what makes people stop
     * answering the questions that matter.
     */
    @ParameterizedTest
    @ValueSource(strings = {"EUR", "USD", "PLN", "USDT", "USDC", "usd"})
    void shouldSettleAnIncreaseOfAnAssetHeldAtPar(String ticker) {
        Difference difference = ResolutionRules.forIncrease(
                Ticker.of(ticker), SubName.none(), Quantity.of(500), null);

        assertThat(difference.needsAnswer()).isFalse();
        assertThat(difference.resolvedCost().provenance()).isEqualTo(Provenance.ASSUMED_PAR);
        assertThat(difference.resolvedCost().avgPrice()).isEqualTo(Price.one(ticker));
    }

    @Test
    void shouldAskAboutAnIncreaseNobodyPriced() {
        Difference difference = ResolutionRules.forIncrease(
                BTC, SubName.transferredIn(), Quantity.of(1), null);

        assertThat(difference.needsAnswer()).isTrue();
        assertThat(difference.question()).isEqualTo(QuestionKind.ACQUISITION_COST);
        assertThat(difference.resolvedCost()).isNull();
    }

    /**
     * A decrease always asks in the POC. §4.2 says a matching fill should settle it, but that
     * needs trade history the backend does not fetch - see the note on ResolutionRules. This test
     * records the limitation so it is visible rather than forgotten.
     */
    @Test
    void shouldAskAboutEveryDecreaseWhileFillHistoryIsUnavailable() {
        Difference difference = ResolutionRules.forDecrease(BTC, SubName.traded(), Quantity.of(0.2));

        assertThat(difference.needsAnswer()).isTrue();
        assertThat(difference.question()).isEqualTo(QuestionKind.DISPOSAL_REASON);
    }

    @Test
    void shouldAskAboutCashLeavingTheAccountToo() {
        Difference difference = ResolutionRules.forDecrease(
                Ticker.of("EUR"), SubName.none(), Quantity.of(1_000));

        assertThat(difference.question())
                .as("money leaving is a withdrawal or a move between the user's own accounts")
                .isEqualTo(QuestionKind.DISPOSAL_REASON);
    }
}
