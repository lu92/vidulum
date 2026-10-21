package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.OrderStatus;
import com.multi.vidulum.common.OrderType;
import com.multi.vidulum.common.OriginOrderId;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.OrderSnapshot;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The version has to survive entity → snapshot → aggregate → snapshot → entity, because
 * {@code save} rebuilds the entity from the aggregate's snapshot. Lose it anywhere on that path and
 * it reaches Mongo as null, Spring Data reads null as "new document", and the update becomes an
 * insert.
 *
 * <p><b>This test exists because the compiler cannot catch it here.</b> Both mappings in
 * {@link OrderEntity} go through Lombok builders, so adding {@code version} to
 * {@link OrderSnapshot} compiled clean while the value was dropped in both directions - the exact
 * failure CLAUDE.md warns about for builders, and the reason portfolio's all-args constructor sites
 * broke loudly instead.
 */
class OrderVersionRoundTripTest {

    private static final ZonedDateTime WHEN = ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]");

    @Test
    void theStoredVersionSurvivesTheWholeWayBackToTheEntity() {
        OrderEntity stored = OrderEntity.fromSnapshot(snapshotWithVersion(7L));

        assertThat(stored.getVersion())
                .as("entity built from a snapshot that carried a version")
                .isEqualTo(7L);

        Order aggregate = Order.from(stored.toSnapshot());
        assertThat(aggregate.getSnapshot().getVersion())
                .as("read back through the aggregate")
                .isEqualTo(7L);

        assertThat(OrderEntity.fromSnapshot(aggregate.getSnapshot()).getVersion())
                .as("and on to the entity the next save writes")
                .isEqualTo(7L);
    }

    @Test
    void anOrderThatWasNeverStoredCarriesNoVersion() {
        OrderEntity fresh = OrderEntity.fromSnapshot(snapshotWithVersion(null));

        assertThat(fresh.getVersion())
                .as("null is what tells Spring Data to insert, which is right for a new order")
                .isNull();
    }

    private static OrderSnapshot snapshotWithVersion(Long version) {
        return OrderSnapshot.builder()
                .orderId(OrderId.of("OR1"))
                .originOrderId(OriginOrderId.of("origin-1"))
                .portfolioId(PortfolioId.of("PF1"))
                .broker(Broker.of("XTB"))
                .symbol(Symbol.of("BTC/USD"))
                .state(new Order.OrderState(OrderStatus.OPEN, List.of()))
                .parameters(new Order.OrderParameters(
                        OrderType.LIMIT,
                        Side.BUY,
                        null,
                        null,
                        Price.of(100.0, "USD"),
                        Quantity.of(1)))
                .occurredDateTime(WHEN.withZoneSameInstant(ZoneOffset.UTC))
                .version(version)
                .build();
    }
}
