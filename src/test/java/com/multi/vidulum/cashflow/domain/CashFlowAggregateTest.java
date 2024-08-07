package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Reason;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.*;
import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;
import static org.assertj.core.api.Assertions.assertThat;

class CashFlowAggregateTest extends IntegrationTest {

    @Autowired
    private Clock clock;

    @Autowired
    private CashFlowAggregateProjector cashFlowAggregateProjector;

    @Test
    void shouldSaveNewlyCreatedCashChange() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();
        cashFlow.apply(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("description"),
                        new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"))
        );

        cashFlow.apply(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        cashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                )
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
                                new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(0, "USD")),
                                CashFlow.CashFlowStatus.OPEN,
                                Map.of(
                                        cashChangeId,
                                        new CashChangeSnapshot(
                                                cashChangeId,
                                                new Name("cash change name"),
                                                new Description("cash change description"),
                                                Money.of(100, "USD"),
                                                INFLOW,
                                                new CategoryName("Uncategorized"),
                                                PENDING,
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                                null
                                        )),
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                null
                        ));

        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId)).containsExactly(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("user"),
                        new Name("name"),
                        new Description("description"),
                        new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        cashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                )
        );

        List<CashFlowEvent> domainEvents = domainCashFlowRepository.findDomainEvents(cashFlowId)
                .stream()
                .map(domainEvent -> (CashFlowEvent) domainEvent)
                .collect(Collectors.toList());

        CashFlow reprocessedCashFlow = cashFlowAggregateProjector.process(domainEvents);
        assertThat(reprocessedCashFlow.getSnapshot())
                .isEqualTo(domainCashFlowRepository.findById(cashFlowId).get().getSnapshot());
    }

    @Test
    void shouldConfirmCashChange() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId firstCashChangeId = CashChangeId.generate();
        CashChangeId secondCashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();
        cashFlow.apply(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("description"),
                        new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                ));

        cashFlow.apply(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change inflow description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                )
        );

        cashFlow.apply(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change outflow description"),
                        Money.of(60, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                )
        );

        cashFlow.apply(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        ZonedDateTime.parse("2021-07-10T06:30:00Z")
                )
        );

        cashFlow.apply(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        ZonedDateTime.parse("2021-07-10T06:30:00Z")
                )
        );

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
                                new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(40, "USD")),
                                CashFlow.CashFlowStatus.OPEN,
                                Map.of(
                                        firstCashChangeId,
                                        new CashChangeSnapshot(
                                                firstCashChangeId,
                                                new Name("cash change name"),
                                                new Description("cash change inflow description"),
                                                Money.of(100, "USD"),
                                                INFLOW,
                                                new CategoryName("Uncategorized"),
                                                CONFIRMED,
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-07-10T06:30:00Z")
                                        ),

                                        secondCashChangeId,
                                        new CashChangeSnapshot(
                                                secondCashChangeId,
                                                new Name("cash change name"),
                                                new Description("cash change outflow description"),
                                                Money.of(60, "USD"),
                                                OUTFLOW,
                                                new CategoryName("Uncategorized"),
                                                CONFIRMED,
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-07-10T06:30:00Z")
                                        )),
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                null
                        )
                );

        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId)).containsExactly(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("description"),
                        new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                ),
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change inflow description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ),
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change outflow description"),
                        Money.of(60, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ),
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        ZonedDateTime.parse("2021-07-10T06:30:00Z")
                ),
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
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
        CashFlow cashFlow = new CashFlow();
        cashFlow.apply(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("description"),
                        new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                ));

        cashFlow.apply(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        cashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z"))
        );

        cashFlow.apply(
                new CashFlowEvent.CashChangeEditedEvent(
                        cashFlowId,
                        cashChangeId,
                        new Name("name edited"),
                        new Description("description edited"),
                        Money.of(500, "USD"),
                        ZonedDateTime.parse("2021-08-01T00:00:00Z")
                )
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
                                new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(0, "USD")),
                                CashFlow.CashFlowStatus.OPEN,
                                Map.of(
                                        cashChangeId,
                                        new CashChangeSnapshot(
                                                cashChangeId,
                                                new Name("name edited"),
                                                new Description("description edited"),
                                                Money.of(500, "USD"),
                                                INFLOW,
                                                new CategoryName("Uncategorized"),
                                                PENDING,
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-08-01T00:00:00Z"),
                                                null
                                        )),
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                null
                        )
                );

        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId))
                .containsExactly(
                        new CashFlowEvent.CashFlowCreatedEvent(
                                cashFlowId,
                                UserId.of("user"),
                                new Name("name"),
                                new Description("description"),
                                new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(0, "USD")),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z")
                        ),
                        new CashFlowEvent.CashChangeAppendedEvent(
                                cashFlowId,
                                cashChangeId,
                                new Name("cash change name"),
                                new Description("cash change description"),
                                Money.of(100, "USD"),
                                INFLOW,
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                new CategoryName("Uncategorized"),
                                ZonedDateTime.parse("2021-07-01T06:30:00Z")
                        ),
                        new CashFlowEvent.CashChangeEditedEvent(
                                cashFlowId,
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

        CashFlow cashFlow = new CashFlow();
        cashFlow.apply(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("description"),
                        new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        cashFlow.apply(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        cashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                )
        );

        cashFlow.apply(
                new CashFlowEvent.CashChangeRejectedEvent(
                        cashFlowId,
                        cashChangeId,
                        new Reason("some reason")
                )
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
                                new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(0, "USD")),
                                CashFlow.CashFlowStatus.OPEN,
                                Map.of(
                                        cashChangeId,
                                        new CashChangeSnapshot(
                                                cashChangeId,
                                                new Name("cash change name"),
                                                new Description("cash change description"),
                                                Money.of(100, "USD"),
                                                INFLOW,
                                                new CategoryName("Uncategorized"),
                                                REJECTED,
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                                null
                                        )),
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                null
                        )
                );


        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId))
                .contains(
                        new CashFlowEvent.CashFlowCreatedEvent(
                                cashFlowId,
                                UserId.of("user"),
                                new Name("name"),
                                new Description("description"),
                                new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(0, "USD")),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z")
                        ),
                        new CashFlowEvent.CashChangeAppendedEvent(
                                cashFlowId,
                                cashChangeId,
                                new Name("cash change name"),
                                new Description("cash change description"),
                                Money.of(100, "USD"),
                                INFLOW,
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                new CategoryName("Uncategorized"),
                                ZonedDateTime.parse("2021-07-01T06:30:00Z")
                        ),
                        new CashFlowEvent.CashChangeRejectedEvent(
                                cashFlowId,
                                cashChangeId,
                                new Reason("some reason")
                        )
                );
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

    @Test
    void shouldAttestMonth() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashFlow cashFlow = new CashFlow();
        cashFlow.apply(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("description"),
                        new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                ));

        cashFlow.apply(
                new CashFlowEvent.MonthAttestedEvent(
                        cashFlowId,
                        YearMonth.from(ZonedDateTime.parse("2021-07-01T06:30:00Z")),
                        Money.of(500, "USD"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                )
        );

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
                                new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(500, "USD")),
                                CashFlow.CashFlowStatus.OPEN,
                                Map.of(),
                                YearMonth.from(ZonedDateTime.parse("2021-07-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                null
                        )
                );

        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId)).containsExactly(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("description"),
                        new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                ),
                new CashFlowEvent.MonthAttestedEvent(
                        cashFlowId,
                        YearMonth.from(ZonedDateTime.parse("2021-07-01T06:30:00Z")),
                        Money.of(500, "USD"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                )
        );

        List<CashFlowEvent> domainEvents = domainCashFlowRepository.findDomainEvents(cashFlowId)
                .stream()
                .map(domainEvent -> (CashFlowEvent) domainEvent)
                .collect(Collectors.toList());

        assertThat(cashFlowAggregateProjector.process(domainEvents).getSnapshot())
                .isEqualTo(domainCashFlowRepository.findById(cashFlowId).get().getSnapshot());

    }
}
