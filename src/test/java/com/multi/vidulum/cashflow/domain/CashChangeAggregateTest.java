package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.ZonedDateTime;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.CONFIRMED;
import static com.multi.vidulum.cashflow.domain.CashChangeStatus.PENDING;
import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CashChangeAggregateTest extends IntegrationTest {

    @Autowired
    private Clock clock;

    @Test
    void shouldSaveNewlyCreatedCashChange() {
        // given
        CashChangeId cashChangeId = CashChangeId.generate();
        CashChange cashChange = cashChangeFactory.empty(
                cashChangeId,
                UserId.of("user"),
                new Name("name"),
                new Description("desc"),
                Money.of(100, "USD"),
                INFLOW,
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
                        INFLOW,
                        PENDING,
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
                        INFLOW,
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
                                INFLOW,
                                PENDING,
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                null
                        )
                );
    }

    @Test
    void shouldConfirmCashChange() {
        // given
        CashChangeId cashChangeId = CashChangeId.generate();
        CashChange cashChange = cashChangeFactory.empty(
                cashChangeId,
                UserId.of("user"),
                new Name("name"),
                new Description("desc"),
                Money.of(100, "USD"),
                OUTFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        );

        cashChange.confirm(ZonedDateTime.parse("2021-07-10T06:30:00Z"));

        // when
        domainCashChangeRepository.save(cashChange);

        // then
        assertThat(domainCashChangeRepository.findById(cashChangeId)).isPresent()
                .map(CashChange::getSnapshot)
                .get()
                .isEqualTo(new CashChangeSnapshot(
                                cashChangeId,
                                UserId.of("user"),
                                new Name("name"),
                                new Description("desc"),
                                Money.of(100, "USD"),
                                OUTFLOW,
                                CONFIRMED,
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-07-10T06:30:00Z")
                        )
                );

        assertThat(domainCashChangeRepository.findDomainEvents(cashChangeId)).containsExactly(
                new CashChangeEvent.CashChangeCreatedEvent(
                        cashChangeId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("desc"),
                        Money.of(100, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ),
                new CashChangeEvent.CashChangeConfirmedEvent(
                        cashChangeId,
                        ZonedDateTime.parse("2021-07-10T06:30:00Z")
                )
        );

    }

    @Test
    void doubleConfirmation_exceptionIsExpected() {
        // given
        CashChangeId cashChangeId = CashChangeId.generate();
        CashChange cashChange = cashChangeFactory.empty(
                cashChangeId,
                UserId.of("user"),
                new Name("name"),
                new Description("desc"),
                Money.of(100, "USD"),
                OUTFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        );

        cashChange.confirm(ZonedDateTime.parse("2021-07-01T06:30:00Z"));

        // when and then
        assertThatThrownBy(() -> cashChange.confirm(ZonedDateTime.parse("2021-07-01T06:30:00Z")))
                .isInstanceOf(CashChangeIsNotOpenedException.class);
    }
}
