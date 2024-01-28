package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Reason;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.*;
import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static org.assertj.core.api.Assertions.assertThat;

class CashChangeAggregateTest extends IntegrationTest {

    @Autowired
    private Clock clock;

    @Test
    void shouldSaveNewlyCreatedCashChange() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                Money.zero("USD"),
                CashFlow.CashFlowStatus.OPEN,
                new HashMap<>(),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                null,
                new LinkedList<>()
        );

        cashFlow.appendCashChange(
                cashChangeId,
                new Name("cash change name"),
                new Description("cash change description"),
                Money.of(100, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        );

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        assertThat(domainCashFlowRepository.findById(cashFlowId))
                .isPresent()
                .map(CashFlow::getSnapshot)
                .get()
                .isEqualTo(
                        new CashFlowSnapshot(
                                cashFlowId,
                                new UserId("user"),
                                new Name("name"),
                                new Description("description"),
                                Money.zero("USD"),
                                CashFlow.CashFlowStatus.OPEN,
                                Map.of(
                                        cashChangeId,
                                        new CashChangeSnapshot(
                                                cashChangeId,
                                                new Name("cash change name"),
                                                new Description("cash change description"),
                                                Money.of(100, "USD"),
                                                INFLOW,
                                                PENDING,
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                                null
                                        )),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                null
                        ));

        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId)).containsExactly(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashChangeId,
                        UserId.of("user"),
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                )
        );
    }

    @Test
    void shouldConfirmCashChange() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                Money.zero("USD"),
                CashFlow.CashFlowStatus.OPEN,
                new HashMap<>(),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                null,
                new LinkedList<>()
        );

        cashFlow.appendCashChange(
                cashChangeId,
                new Name("cash change name"),
                new Description("cash change description"),
                Money.of(100, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        );

        cashFlow.confirm(cashChangeId, ZonedDateTime.parse("2021-07-10T06:30:00Z"));

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        assertThat(domainCashFlowRepository.findById(cashFlowId)).isPresent()
                .map(CashFlow::getSnapshot)
                .get()
                .isEqualTo(
                        new CashFlowSnapshot(
                                cashFlowId,
                                new UserId("user"),
                                new Name("name"),
                                new Description("description"),
                                Money.zero("USD"),
                                CashFlow.CashFlowStatus.OPEN,
                                Map.of(
                                        cashChangeId,
                                        new CashChangeSnapshot(
                                                cashChangeId,
                                                new Name("cash change name"),
                                                new Description("cash change description"),
                                                Money.of(100, "USD"),
                                                INFLOW,
                                                CONFIRMED,
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-07-10T06:30:00Z")
                                        )),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                null
                        )
                );

        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId)).containsExactly(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashChangeId,
                        UserId.of("user"),
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ),
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashChangeId,
                        ZonedDateTime.parse("2021-07-10T06:30:00Z")
                )
        );

    }

    //
//    @Test
//    void doubleConfirmation_exceptionIsExpected() {
//        // given
//        CashChangeId cashChangeId = CashChangeId.generate();
//        CashChange cashChange = cashChangeFactory.empty(
//                cashChangeId,
//                UserId.of("user"),
//                new Name("name"),
//                new Description("desc"),
//                Money.of(100, "USD"),
//                OUTFLOW,
//                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
//                ZonedDateTime.parse("2021-07-01T06:30:00Z")
//        );
//
//        cashChange.confirm(ZonedDateTime.parse("2021-07-01T06:30:00Z"));
//
//        // when and then
//        assertThatThrownBy(() -> cashChange.confirm(ZonedDateTime.parse("2021-07-01T06:30:00Z")))
//                .isInstanceOf(CashChangeIsNotOpenedException.class);
//    }
//
    @Test
    void shouldEditCashChangeTest() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                Money.zero("USD"),
                CashFlow.CashFlowStatus.OPEN,
                new HashMap<>(),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                null,
                new LinkedList<>()
        );

        cashFlow.appendCashChange(
                cashChangeId,
                new Name("cash change name"),
                new Description("cash change description"),
                Money.of(100, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        );

        cashFlow.edit(
                cashChangeId,
                new Name("name edited"),
                new Description("description edited"),
                Money.of(500, "USD"),
                ZonedDateTime.parse("2021-08-01T00:00:00Z"));

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        assertThat(domainCashFlowRepository.findById(cashFlowId))
                .isPresent()
                .map(CashFlow::getSnapshot)
                .get()
                .isEqualTo(
                        new CashFlowSnapshot(
                                cashFlowId,
                                new UserId("user"),
                                new Name("name"),
                                new Description("description"),
                                Money.zero("USD"),
                                CashFlow.CashFlowStatus.OPEN,
                                Map.of(
                                        cashChangeId,
                                        new CashChangeSnapshot(
                                                cashChangeId,
                                                new Name("name edited"),
                                                new Description("description edited"),
                                                Money.of(500, "USD"),
                                                INFLOW,
                                                PENDING,
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-08-01T00:00:00Z"),
                                                null
                                        )),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                null
                        )
                );

        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId))
                .containsExactly(
                        new CashFlowEvent.CashChangeAppendedEvent(
                                cashChangeId,
                                UserId.of("user"),
                                new Name("cash change name"),
                                new Description("cash change description"),
                                Money.of(100, "USD"),
                                INFLOW,
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-07-01T06:30:00Z")
                        ),
                        new CashFlowEvent.CashChangeEditedEvent(
                                cashChangeId,
                                new Name("name edited"),
                                new Description("description edited"),
                                Money.of(500, "USD"),
                                ZonedDateTime.parse("2021-08-01T00:00:00Z")
                        )
                );
    }

    //
//    @Test
//    void modificationOnConfirmedCashChange_exceptionIsExpected() {
//        // given
//        CashChangeId cashChangeId = CashChangeId.generate();
//        CashChange cashChange = cashChangeFactory.empty(
//                cashChangeId,
//                UserId.of("user"),
//                new Name("name"),
//                new Description("desc"),
//                Money.of(100, "USD"),
//                OUTFLOW,
//                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
//                ZonedDateTime.parse("2021-07-01T06:30:00Z")
//        );
//
//        cashChange.confirm(ZonedDateTime.parse("2021-07-10T06:30:00Z"));
//
//        // when
//        assertThatThrownBy(() -> cashChange.edit(
//                new Name("name edited"),
//                new Description("description edited"),
//                Money.of(500, "USD"),
//                ZonedDateTime.parse("2021-08-01T00:00:00Z")))
//                .isInstanceOf(CashChangeIsNotOpenedException.class);
//    }
//
    @Test
    void shouldRejectCashChangeTest() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                Money.zero("USD"),
                CashFlow.CashFlowStatus.OPEN,
                new HashMap<>(),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                null,
                new LinkedList<>()
        );

        cashFlow.appendCashChange(
                cashChangeId,
                new Name("cash change name"),
                new Description("cash change description"),
                Money.of(100, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        );
        cashFlow.reject(cashChangeId, new Reason("some reason"));

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        assertThat(domainCashFlowRepository.findById(cashFlowId))
                .isPresent()
                .map(CashFlow::getSnapshot)
                .get()
                .isEqualTo(
                        new CashFlowSnapshot(
                                cashFlowId,
                                new UserId("user"),
                                new Name("name"),
                                new Description("description"),
                                Money.zero("USD"),
                                CashFlow.CashFlowStatus.OPEN,
                                Map.of(
                                        cashChangeId,
                                        new CashChangeSnapshot(
                                                cashChangeId,
                                                new Name("cash change name"),
                                                new Description("cash change description"),
                                                Money.of(100, "USD"),
                                                INFLOW,
                                                REJECTED,
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                                null
                                        )),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                null
                        )
                );


//        assertThat(domainCashChangeRepository.findDomainEvents(cashChangeId))
//                .containsExactly(
//                        new CashFlowEvent.CashChangeCreatedEvent(
//                                cashChangeId,
//                                UserId.of("user"),
//                                new Name("name"),
//                                new Description("description"),
//                                Money.of(100, "USD"),
//                                Type.INFLOW,
//                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
//                                ZonedDateTime.parse("2021-07-01T06:30:00Z")
//                        ),
//                        new CashFlowEvent.CashChangeRejectedEvent(
//                                cashChangeId,
//                                new Reason("some reason")
//                        )
//                );
    }
//
//    @Test
//    void rejectionOnAlreadyRejectedCashChange_exceptionExpected() {
//        // given
//        CashChangeId cashChangeId = CashChangeId.generate();
//        CashChange cashChange = cashChangeFactory.empty(
//                cashChangeId,
//                UserId.of("user"),
//                new Name("name"),
//                new Description("description"),
//                Money.of(100, "USD"),
//                Type.INFLOW,
//                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
//                ZonedDateTime.parse("2021-07-01T06:30:00Z")
//        );
//
//        // when
//        cashChange.reject(new Reason("some reason"));
//        assertThatThrownBy(() -> cashChange.reject(new Reason("some reason")))
//                .isInstanceOf(CashChangeIsNotOpenedException.class);
//
//    }
}
