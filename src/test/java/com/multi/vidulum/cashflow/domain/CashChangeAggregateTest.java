package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CashChangeAggregateTest extends IntegrationTest {

    @Test
    void shouldSaveNewlyCreatedCashChangeTest() {
        // given
        CashChangeId cashChangeId = CashChangeId.generate();
        CashChange cashChange = cashChangeFactory.empty(
                cashChangeId,
                UserId.of("user"),
                new Name("name"),
                new Description("desc"),
                Money.of(100, "USD"),
                Type.INFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        );

        // when
        CashChange savedCashChange = domainCashChangeRepository.save(cashChange);

        // then
        assertThat(savedCashChange.getSnapshot()).isEqualTo(
                new CashChangeSnapshot(
                        cashChangeId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("desc"),
                        Money.of(100, "USD"),
                        Type.INFLOW,
                        CashChangeStatus.PENDING,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                        null
                )
        );

        assertThat(domainCashChangeRepository.findDomainEvents(savedCashChange.getSnapshot().cashChangeId())).containsExactly(
                new CashChangeEvent.CashChangeCreatedEvent(
                        cashChangeId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("desc"),
                        Money.of(100, "USD"),
                        Type.INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                )
        );

        assertThat(domainCashChangeRepository.findById(cashChangeId)).isPresent()
                .map(CashChange::getSnapshot)
                .get()
                .isEqualTo(new CashChangeSnapshot(
                                cashChangeId,
                                UserId.of("user"),
                                new Name("name"),
                                new Description("desc"),
                                Money.of(100, "USD"),
                                Type.INFLOW,
                                CashChangeStatus.PENDING,
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                null
                        )
                );
    }
}
