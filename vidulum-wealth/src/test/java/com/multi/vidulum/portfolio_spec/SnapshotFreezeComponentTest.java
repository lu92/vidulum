package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.portfolio_spec.domain.SnapshotPosition;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the exchange has committed, and what the snapshot is allowed to say about it (task D5).
 *
 * <p>Until now every position onboarded from a snapshot arrived entirely free, so an account with
 * an open order told the owner they could move money that the exchange would refuse to release.
 *
 * <p>The freeze is reported per currency and summed across Trading and Funding — the owner has one
 * balance, not two — while positions are split by where they came from (C2). The two do not line
 * up, and cannot: units are fungible, so no fact says which bitcoin an order committed. The rule
 * is in {@code ConfirmPortfolioSpecCommandHandler}; what is checked here is that the snapshot
 * itself cannot state something impossible.
 */
class SnapshotFreezeComponentTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");

    private static SnapshotPosition position(double total, double traded, Double frozen) {
        return new SnapshotPosition(
                Ticker.of("BTC"), Quantity.of(total), Quantity.of(traded),
                frozen == null ? null : Quantity.of(frozen),
                Price.of(50_000, "USD"));
    }

    @Test
    void shouldReportWhatIsLeftToActOn() {
        SnapshotPosition position = position(2, 1, 0.5);

        assertThat(position.available()).isEqualTo(Quantity.of(1.5));
    }

    /**
     * A venue reporting no freeze column at all is saying nothing is frozen. Absence is only a
     * claim where the alternative is a guess — as it is for {@code traded}, which decides cost.
     */
    @Test
    void shouldReadAMissingFreezeAsNothingFrozen() {
        SnapshotPosition position = position(2, 1, null);

        assertThat(position.frozen()).isEqualTo(Quantity.zero("Number"));
        assertThat(position.available()).isEqualTo(Quantity.of(2));
    }

    @Test
    void shouldRefuseAFreezeLargerThanWhatIsHeld() {
        assertThatThrownBy(() -> position(1, 1, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the total held");
    }

    @Test
    void shouldRefuseANegativeFreeze() {
        assertThatThrownBy(() -> position(1, 1, -0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }

    /**
     * The freeze cuts across the traded split rather than following it — a position can be fully
     * traded, fully transferred in, and frozen in either case.
     */
    @Test
    void shouldAllowAFreezeOnAPositionTheExchangeNeverPriced() {
        SnapshotPosition transferredIn = new SnapshotPosition(
                Ticker.of("XRP"), Quantity.of(50_000), Quantity.zero("Number"),
                Quantity.of(10_000), null);

        assertThat(transferredIn.transferredIn()).isEqualTo(Quantity.of(50_000));
        assertThat(transferredIn.available()).isEqualTo(Quantity.of(40_000));
        assertThat(transferredIn.hasReportedPrice()).isFalse();
    }

    @Test
    void shouldTravelWithTheSnapshot() {
        ExchangeSnapshot snapshot = new ExchangeSnapshot(
                Broker.of("OKX"), NOW, List.of(position(2, 1, 0.5)));

        assertThat(snapshot.find(Ticker.of("BTC")).orElseThrow().frozen())
                .isEqualTo(Quantity.of(0.5));
    }

    /** Cash is one position under {@code none} (C10), and it freezes like anything else. */
    @Test
    void shouldFreezeCashToo() {
        SnapshotPosition cash = new SnapshotPosition(
                Ticker.of("EUR"), Quantity.of(4_000), Quantity.zero("Number"),
                Quantity.of(1_000), null);

        assertThat(cash.available()).isEqualTo(Quantity.of(3_000));
        assertThat(SubName.none()).isNotNull();
        assertThat(Currency.of("EUR").getId()).isEqualTo("EUR");
    }
}
