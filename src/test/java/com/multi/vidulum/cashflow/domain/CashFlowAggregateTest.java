package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.*;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CashFlowAggregateTest extends IntegrationTest {

    @Autowired
    private Clock clock;

    @Autowired
    private CashFlowAggregateProjector cashFlowAggregateProjector;

    private Checksum calculateChecksum(CashFlowEvent event) {
        String jsonizedEvent = JsonContent.asJson(event).content();
        return new Checksum(DigestUtils.md5DigestAsHex(jsonizedEvent.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldSaveNewlyCreatedCashChange() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();
        CashFlowEvent.CashFlowCreatedEvent createdEvent = new CashFlowEvent.CashFlowCreatedEvent(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                new BankAccount(
                        new BankName("bank"),
                        new BankAccountNumber("account number", Currency.of("USD")),
                        Money.of(0, "USD")),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"));
        cashFlow.apply(createdEvent);

        CashFlowEvent.ExpectedCashChangeAppendedEvent appendedEvent = new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                cashFlowId,
                cashChangeId,
                new Name("cash change name"),
                new Description("cash change description"),
                Money.of(100, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        );
        cashFlow.apply(appendedEvent);

        Checksum expectedChecksum = calculateChecksum(appendedEvent);

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        CashFlowSnapshot actualSnapshot = domainCashFlowRepository.findById(cashFlowId)
                .map(CashFlow::getSnapshot)
                .orElseThrow();

        assertThat(actualSnapshot)
                .usingRecursiveComparison()
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
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                expectedChecksum
                        ));

        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId)).containsExactly(
                createdEvent,
                appendedEvent
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
    void shouldSavePaidCashChangeWithConfirmedStatus() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();
        CashFlowEvent.CashFlowCreatedEvent createdEvent = new CashFlowEvent.CashFlowCreatedEvent(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                new BankAccount(
                        new BankName("bank"),
                        new BankAccountNumber("account number", Currency.of("USD")),
                        Money.of(1000, "USD")),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"));
        cashFlow.apply(createdEvent);

        CashFlowEvent.PaidCashChangeAppendedEvent paidEvent = new CashFlowEvent.PaidCashChangeAppendedEvent(
                cashFlowId,
                cashChangeId,
                new Name("paid cash change name"),
                new Description("paid cash change description"),
                Money.of(150, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        );
        cashFlow.apply(paidEvent);

        Checksum expectedChecksum = calculateChecksum(paidEvent);

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        CashFlowSnapshot actualSnapshot = domainCashFlowRepository.findById(cashFlowId)
                .map(CashFlow::getSnapshot)
                .orElseThrow();

        // Verify CashChange is CONFIRMED immediately
        assertThat(actualSnapshot.cashChanges().get(cashChangeId).status()).isEqualTo(CONFIRMED);
        // Verify endDate is set to paidDate
        assertThat(actualSnapshot.cashChanges().get(cashChangeId).endDate()).isEqualTo(ZonedDateTime.parse("2021-06-01T06:30:00Z"));
        // Verify balance is updated (1000 + 150 = 1150 for INFLOW)
        assertThat(actualSnapshot.bankAccount().balance()).isEqualTo(Money.of(1150, "USD"));

        assertThat(actualSnapshot)
                .usingRecursiveComparison()
                .isEqualTo(
                        new CashFlowSnapshot(
                                cashFlowId,
                                new UserId("user"),
                                new Name("name"),
                                new Description("description"),
                                new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(1150, "USD")),
                                CashFlow.CashFlowStatus.OPEN,
                                Map.of(
                                        cashChangeId,
                                        new CashChangeSnapshot(
                                                cashChangeId,
                                                new Name("paid cash change name"),
                                                new Description("paid cash change description"),
                                                Money.of(150, "USD"),
                                                INFLOW,
                                                new CategoryName("Uncategorized"),
                                                CONFIRMED,
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                                ZonedDateTime.parse("2021-06-01T06:30:00Z")
                                        )),
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                expectedChecksum
                        ));

        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId)).containsExactly(
                createdEvent,
                paidEvent
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
    void shouldSavePaidCashChangeOutflowAndDecreaseBalance() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();
        CashFlowEvent.CashFlowCreatedEvent createdEvent = new CashFlowEvent.CashFlowCreatedEvent(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                new BankAccount(
                        new BankName("bank"),
                        new BankAccountNumber("account number", Currency.of("USD")),
                        Money.of(500, "USD")),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"));
        cashFlow.apply(createdEvent);

        CashFlowEvent.PaidCashChangeAppendedEvent paidEvent = new CashFlowEvent.PaidCashChangeAppendedEvent(
                cashFlowId,
                cashChangeId,
                new Name("grocery shopping"),
                new Description("weekly groceries"),
                Money.of(75, "USD"),
                OUTFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        );
        cashFlow.apply(paidEvent);

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        CashFlowSnapshot actualSnapshot = domainCashFlowRepository.findById(cashFlowId)
                .map(CashFlow::getSnapshot)
                .orElseThrow();

        // Verify CashChange is CONFIRMED immediately
        assertThat(actualSnapshot.cashChanges().get(cashChangeId).status()).isEqualTo(CONFIRMED);
        // Verify balance is decreased (500 - 75 = 425 for OUTFLOW)
        assertThat(actualSnapshot.bankAccount().balance()).isEqualTo(Money.of(425, "USD"));

        List<CashFlowEvent> domainEvents = domainCashFlowRepository.findDomainEvents(cashFlowId)
                .stream()
                .map(domainEvent -> (CashFlowEvent) domainEvent)
                .collect(Collectors.toList());

        CashFlow reprocessedCashFlow = cashFlowAggregateProjector.process(domainEvents);
        assertThat(reprocessedCashFlow.getSnapshot())
                .isEqualTo(domainCashFlowRepository.findById(cashFlowId).get().getSnapshot());
    }

    @Test
    void shouldHandleMultiplePaidCashChanges() {
        // given
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId inflowId = CashChangeId.generate();
        CashChangeId outflow1Id = CashChangeId.generate();
        CashChangeId outflow2Id = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();

        cashFlow.apply(new CashFlowEvent.CashFlowCreatedEvent(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                new BankAccount(
                        new BankName("bank"),
                        new BankAccountNumber("account number", Currency.of("USD")),
                        Money.of(1000, "USD")),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")));

        // Add inflow: +500
        cashFlow.apply(new CashFlowEvent.PaidCashChangeAppendedEvent(
                cashFlowId,
                inflowId,
                new Name("salary"),
                new Description("monthly salary"),
                Money.of(500, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        ));

        // Add outflow: -100
        cashFlow.apply(new CashFlowEvent.PaidCashChangeAppendedEvent(
                cashFlowId,
                outflow1Id,
                new Name("groceries"),
                new Description("food"),
                Money.of(100, "USD"),
                OUTFLOW,
                ZonedDateTime.parse("2021-06-02T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-06-02T06:30:00Z"),
                ZonedDateTime.parse("2021-06-02T06:30:00Z")
        ));

        // Add outflow: -50
        cashFlow.apply(new CashFlowEvent.PaidCashChangeAppendedEvent(
                cashFlowId,
                outflow2Id,
                new Name("fuel"),
                new Description("gas station"),
                Money.of(50, "USD"),
                OUTFLOW,
                ZonedDateTime.parse("2021-06-03T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-06-03T06:30:00Z"),
                ZonedDateTime.parse("2021-06-03T06:30:00Z")
        ));

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        CashFlowSnapshot actualSnapshot = domainCashFlowRepository.findById(cashFlowId)
                .map(CashFlow::getSnapshot)
                .orElseThrow();

        // Verify all CashChanges are CONFIRMED
        assertThat(actualSnapshot.cashChanges().get(inflowId).status()).isEqualTo(CONFIRMED);
        assertThat(actualSnapshot.cashChanges().get(outflow1Id).status()).isEqualTo(CONFIRMED);
        assertThat(actualSnapshot.cashChanges().get(outflow2Id).status()).isEqualTo(CONFIRMED);

        // Verify balance: 1000 + 500 - 100 - 50 = 1350
        assertThat(actualSnapshot.bankAccount().balance()).isEqualTo(Money.of(1350, "USD"));

        // Verify projection works correctly
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

        cashFlow.apply(new CashFlowEvent.CashFlowCreatedEvent(
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

        cashFlow.apply(new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                cashFlowId,
                firstCashChangeId,
                new Name("cash change name"),
                new Description("cash change inflow description"),
                Money.of(100, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        ));

        cashFlow.apply(new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                cashFlowId,
                secondCashChangeId,
                new Name("cash change name"),
                new Description("cash change outflow description"),
                Money.of(60, "USD"),
                OUTFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        ));

        cashFlow.apply(new CashFlowEvent.CashChangeConfirmedEvent(
                cashFlowId,
                firstCashChangeId,
                ZonedDateTime.parse("2021-07-10T06:30:00Z")
        ));

        CashFlowEvent.CashChangeConfirmedEvent lastEvent = new CashFlowEvent.CashChangeConfirmedEvent(
                cashFlowId,
                secondCashChangeId,
                ZonedDateTime.parse("2021-07-10T06:30:00Z")
        );
        cashFlow.apply(lastEvent);

        Checksum expectedChecksum = calculateChecksum(lastEvent);

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        CashFlowSnapshot actualSnapshot = domainCashFlowRepository.findById(cashFlowId)
                .map(CashFlow::getSnapshot)
                .orElseThrow();

        assertThat(actualSnapshot)
                .usingRecursiveComparison()
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
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-07-10T06:30:00Z"),
                                expectedChecksum
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
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
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
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
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
        cashFlow.apply(new CashFlowEvent.CashFlowCreatedEvent(
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

        cashFlow.apply(new CashFlowEvent.ExpectedCashChangeAppendedEvent(
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

        CashFlowEvent.CashChangeEditedEvent lastEvent = new CashFlowEvent.CashChangeEditedEvent(
                cashFlowId,
                cashChangeId,
                new Name("name edited"),
                new Description("description edited"),
                Money.of(500, "USD"),
                ZonedDateTime.parse("2021-08-01T00:00:00Z"),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        );
        cashFlow.apply(lastEvent);

        Checksum expectedChecksum = calculateChecksum(lastEvent);

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        CashFlowSnapshot actualSnapshot = domainCashFlowRepository.findById(cashFlowId)
                .map(CashFlow::getSnapshot)
                .orElseThrow();

        assertThat(actualSnapshot)
                .usingRecursiveComparison()
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
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                expectedChecksum
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
                        new CashFlowEvent.ExpectedCashChangeAppendedEvent(
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
                                ZonedDateTime.parse("2021-08-01T00:00:00Z"),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z")
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
        cashFlow.apply(new CashFlowEvent.CashFlowCreatedEvent(
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

        cashFlow.apply(new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                cashFlowId,
                cashChangeId,
                new Name("cash change name"),
                new Description("cash change description"),
                Money.of(100, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        ));

        CashFlowEvent.CashChangeRejectedEvent lastEvent = new CashFlowEvent.CashChangeRejectedEvent(
                cashFlowId,
                cashChangeId,
                new Reason("some reason"),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        );
        cashFlow.apply(lastEvent);

        Checksum expectedChecksum = calculateChecksum(lastEvent);

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        CashFlowSnapshot actualSnapshot = domainCashFlowRepository.findById(cashFlowId)
                .map(CashFlow::getSnapshot)
                .orElseThrow();

        assertThat(actualSnapshot)
                .usingRecursiveComparison()
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
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                expectedChecksum
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
                        new CashFlowEvent.ExpectedCashChangeAppendedEvent(
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
                                new Reason("some reason"),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z")
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

        CashFlowEvent.MonthAttestedEvent lastEvent = new CashFlowEvent.MonthAttestedEvent(
                cashFlowId,
                YearMonth.from(ZonedDateTime.parse("2021-07-01T06:30:00Z")),
                Money.of(500, "USD"),
                ZonedDateTime.parse("2021-07-01T06:30:00Z")
        );
        cashFlow.apply(lastEvent);
        Checksum expectedChecksum = calculateChecksum(lastEvent);

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        CashFlowSnapshot actualSnapshot = domainCashFlowRepository.findById(cashFlowId)
                .map(CashFlow::getSnapshot)
                .orElseThrow();

        assertThat(actualSnapshot)
                .usingRecursiveComparison()
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
                                YearMonth.from(ZonedDateTime.parse("2021-06-01T06:30:00Z")),
                                YearMonth.from(ZonedDateTime.parse("2021-07-01T06:30:00Z")),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                null,
                                                new LinkedList<>(),
                                                false
                                        )
                                ),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                                ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                                expectedChecksum
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
                lastEvent
        );

        List<CashFlowEvent> domainEvents = domainCashFlowRepository.findDomainEvents(cashFlowId)
                .stream()
                .map(domainEvent -> (CashFlowEvent) domainEvent)
                .collect(Collectors.toList());

        assertThat(cashFlowAggregateProjector.process(domainEvents).getSnapshot())
                .isEqualTo(domainCashFlowRepository.findById(cashFlowId).get().getSnapshot());

    }

    @Test
    void shouldSetBudgetingAndTrackUncommittedEvents() {
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
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Groceries"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        CashFlowEvent.BudgetingSetEvent budgetingSetEvent = new CashFlowEvent.BudgetingSetEvent(
                cashFlowId,
                new CategoryName("Groceries"),
                OUTFLOW,
                Money.of(500, "USD"),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        );

        // when
        cashFlow.apply(budgetingSetEvent);

        // then - verify uncommitted events contain the budgeting event
        assertThat(cashFlow.getUncommittedEvents())
                .contains(budgetingSetEvent);

        // verify budgeting is set on the category
        CashFlowSnapshot snapshot = cashFlow.getSnapshot();
        assertThat(snapshot.outflowCategories())
                .anySatisfy(category -> {
                    assertThat(category.getCategoryName().name()).isEqualTo("Groceries");
                    assertThat(category.getBudgeting()).isNotNull();
                    assertThat(category.getBudgeting().budget()).isEqualTo(Money.of(500, "USD"));
                });

        // when save
        domainCashFlowRepository.save(cashFlow);

        // then - verify domain events are persisted
        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId))
                .contains(budgetingSetEvent);
    }

    @Test
    void shouldUpdateBudgetingAndTrackUncommittedEvents() {
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
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Entertainment"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        cashFlow.apply(
                new CashFlowEvent.BudgetingSetEvent(
                        cashFlowId,
                        new CategoryName("Entertainment"),
                        OUTFLOW,
                        Money.of(200, "USD"),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        CashFlowEvent.BudgetingUpdatedEvent budgetingUpdatedEvent = new CashFlowEvent.BudgetingUpdatedEvent(
                cashFlowId,
                new CategoryName("Entertainment"),
                OUTFLOW,
                Money.of(350, "USD"),
                ZonedDateTime.parse("2021-06-02T06:30:00Z")
        );

        // when
        cashFlow.apply(budgetingUpdatedEvent);

        // then - verify uncommitted events contain the budgeting updated event
        assertThat(cashFlow.getUncommittedEvents())
                .contains(budgetingUpdatedEvent);

        // verify budgeting is updated on the category
        CashFlowSnapshot snapshot = cashFlow.getSnapshot();
        assertThat(snapshot.outflowCategories())
                .anySatisfy(category -> {
                    assertThat(category.getCategoryName().name()).isEqualTo("Entertainment");
                    assertThat(category.getBudgeting()).isNotNull();
                    assertThat(category.getBudgeting().budget()).isEqualTo(Money.of(350, "USD"));
                });

        // when save
        domainCashFlowRepository.save(cashFlow);

        // then - verify domain events are persisted
        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId))
                .contains(budgetingUpdatedEvent);
    }

    @Test
    void shouldRemoveBudgetingAndTrackUncommittedEvents() {
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
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Dining"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        cashFlow.apply(
                new CashFlowEvent.BudgetingSetEvent(
                        cashFlowId,
                        new CategoryName("Dining"),
                        OUTFLOW,
                        Money.of(150, "USD"),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        CashFlowEvent.BudgetingRemovedEvent budgetingRemovedEvent = new CashFlowEvent.BudgetingRemovedEvent(
                cashFlowId,
                new CategoryName("Dining"),
                OUTFLOW,
                ZonedDateTime.parse("2021-06-02T06:30:00Z")
        );

        // when
        cashFlow.apply(budgetingRemovedEvent);

        // then - verify uncommitted events contain the budgeting removed event
        assertThat(cashFlow.getUncommittedEvents())
                .contains(budgetingRemovedEvent);

        // verify budgeting is removed from the category
        CashFlowSnapshot snapshot = cashFlow.getSnapshot();
        assertThat(snapshot.outflowCategories())
                .anySatisfy(category -> {
                    assertThat(category.getCategoryName().name()).isEqualTo("Dining");
                    assertThat(category.getBudgeting()).isNull();
                });

        // when save
        domainCashFlowRepository.save(cashFlow);

        // then - verify domain events are persisted
        assertThat(domainCashFlowRepository.findDomainEvents(cashFlowId))
                .contains(budgetingRemovedEvent);
    }

    @Test
    void shouldProjectBudgetingEventsCorrectly() {
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
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Salary"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        cashFlow.apply(
                new CashFlowEvent.BudgetingSetEvent(
                        cashFlowId,
                        new CategoryName("Salary"),
                        INFLOW,
                        Money.of(5000, "USD"),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        cashFlow.apply(
                new CashFlowEvent.BudgetingUpdatedEvent(
                        cashFlowId,
                        new CategoryName("Salary"),
                        INFLOW,
                        Money.of(6000, "USD"),
                        ZonedDateTime.parse("2021-06-02T06:30:00Z")
                )
        );

        // when
        domainCashFlowRepository.save(cashFlow);

        // then
        List<CashFlowEvent> domainEvents = domainCashFlowRepository.findDomainEvents(cashFlowId)
                .stream()
                .map(domainEvent -> (CashFlowEvent) domainEvent)
                .collect(Collectors.toList());

        CashFlow reprocessedCashFlow = cashFlowAggregateProjector.process(domainEvents);
        assertThat(reprocessedCashFlow.getSnapshot())
                .isEqualTo(domainCashFlowRepository.findById(cashFlowId).get().getSnapshot());

        // verify the projected cashflow has the correct budgeting
        assertThat(reprocessedCashFlow.getSnapshot().inflowCategories())
                .anySatisfy(category -> {
                    assertThat(category.getCategoryName().name()).isEqualTo("Salary");
                    assertThat(category.getBudgeting()).isNotNull();
                    assertThat(category.getBudgeting().budget()).isEqualTo(Money.of(6000, "USD"));
                });
    }

    @Test
    void shouldRejectPaidCashChangeWhenPaidDateNotInActivePeriod() {
        // given - CashFlow created in June 2021, so active period is 2021-06
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();

        cashFlow.apply(new CashFlowEvent.CashFlowCreatedEvent(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                new BankAccount(
                        new BankName("bank"),
                        new BankAccountNumber("account number", Currency.of("USD")),
                        Money.of(1000, "USD")),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        ));

        // when - trying to add paid cash change with paidDate in July (not active period)
        CashFlowEvent.PaidCashChangeAppendedEvent eventWithWrongPeriod = new CashFlowEvent.PaidCashChangeAppendedEvent(
                cashFlowId,
                cashChangeId,
                new Name("salary"),
                new Description("july salary"),
                Money.of(500, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-07-15T06:30:00Z"),  // created
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-07-15T06:30:00Z"),  // dueDate
                ZonedDateTime.parse("2021-07-15T06:30:00Z")   // paidDate in JULY - wrong!
        );

        // then - should throw exception
        assertThatThrownBy(() -> cashFlow.apply(eventWithWrongPeriod))
                .isInstanceOf(PaidDateNotInActivePeriodException.class)
                .hasMessageContaining("2021-07-15")
                .hasMessageContaining("2021-06");
    }

    @Test
    void shouldRejectPaidCashChangeWhenPaidDateInPastAttestedPeriod() {
        // given - CashFlow created in June, then attested to July
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();

        cashFlow.apply(new CashFlowEvent.CashFlowCreatedEvent(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                new BankAccount(
                        new BankName("bank"),
                        new BankAccountNumber("account number", Currency.of("USD")),
                        Money.of(1000, "USD")),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        ));

        // Attest June, move to July
        cashFlow.apply(new CashFlowEvent.MonthAttestedEvent(
                cashFlowId,
                YearMonth.of(2021, 7),
                Money.of(1000, "USD"),
                ZonedDateTime.parse("2021-07-01T00:00:00Z")
        ));

        // when - trying to add paid cash change with paidDate in June (attested period)
        CashFlowEvent.PaidCashChangeAppendedEvent eventWithAttestedPeriod = new CashFlowEvent.PaidCashChangeAppendedEvent(
                cashFlowId,
                cashChangeId,
                new Name("late payment"),
                new Description("forgot to register in june"),
                Money.of(200, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-07-05T06:30:00Z"),  // created
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-06-20T06:30:00Z"),  // dueDate in June
                ZonedDateTime.parse("2021-06-20T06:30:00Z")   // paidDate in JUNE - attested!
        );

        // then - should throw exception (active period is now July)
        assertThatThrownBy(() -> cashFlow.apply(eventWithAttestedPeriod))
                .isInstanceOf(PaidDateNotInActivePeriodException.class)
                .hasMessageContaining("2021-06-20")
                .hasMessageContaining("2021-07");
    }

    @Test
    void shouldAcceptPaidCashChangeWhenPaidDateInActivePeriod() {
        // given - CashFlow created in June 2021, active period is 2021-06
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();

        cashFlow.apply(new CashFlowEvent.CashFlowCreatedEvent(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                new BankAccount(
                        new BankName("bank"),
                        new BankAccountNumber("account number", Currency.of("USD")),
                        Money.of(1000, "USD")),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        ));

        // when - adding paid cash change with paidDate in June (active period)
        CashFlowEvent.PaidCashChangeAppendedEvent validEvent = new CashFlowEvent.PaidCashChangeAppendedEvent(
                cashFlowId,
                cashChangeId,
                new Name("salary"),
                new Description("june salary"),
                Money.of(500, "USD"),
                INFLOW,
                ZonedDateTime.parse("2021-06-15T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-06-15T06:30:00Z"),
                ZonedDateTime.parse("2021-06-15T06:30:00Z")  // paidDate in June - correct!
        );

        cashFlow.apply(validEvent);

        // then - should succeed and update balance
        domainCashFlowRepository.save(cashFlow);

        CashFlowSnapshot snapshot = domainCashFlowRepository.findById(cashFlowId)
                .map(CashFlow::getSnapshot)
                .orElseThrow();

        assertThat(snapshot.bankAccount().balance()).isEqualTo(Money.of(1500, "USD"));
        assertThat(snapshot.cashChanges().get(cashChangeId).status()).isEqualTo(CONFIRMED);
    }

    @Test
    void shouldRejectPaidCashChangeOutflowWhenPaidDateNotInActivePeriod() {
        // given - CashFlow created in June 2021, so active period is 2021-06
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();

        cashFlow.apply(new CashFlowEvent.CashFlowCreatedEvent(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                new BankAccount(
                        new BankName("bank"),
                        new BankAccountNumber("account number", Currency.of("USD")),
                        Money.of(5000, "USD")),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        ));

        // when - trying to add paid OUTFLOW with paidDate in July (not active period)
        CashFlowEvent.PaidCashChangeAppendedEvent eventWithWrongPeriod = new CashFlowEvent.PaidCashChangeAppendedEvent(
                cashFlowId,
                cashChangeId,
                new Name("rent payment"),
                new Description("july rent"),
                Money.of(1500, "USD"),
                OUTFLOW,
                ZonedDateTime.parse("2021-07-05T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-07-05T06:30:00Z"),
                ZonedDateTime.parse("2021-07-05T06:30:00Z")  // paidDate in JULY - wrong!
        );

        // then - should throw exception
        assertThatThrownBy(() -> cashFlow.apply(eventWithWrongPeriod))
                .isInstanceOf(PaidDateNotInActivePeriodException.class)
                .hasMessageContaining("2021-07-05")
                .hasMessageContaining("2021-06");
    }

    @Test
    void shouldAcceptPaidCashChangeOutflowWhenPaidDateInActivePeriod() {
        // given - CashFlow created in June 2021, active period is 2021-06
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId cashChangeId = CashChangeId.generate();
        CashFlow cashFlow = new CashFlow();

        cashFlow.apply(new CashFlowEvent.CashFlowCreatedEvent(
                cashFlowId,
                UserId.of("user"),
                new Name("name"),
                new Description("description"),
                new BankAccount(
                        new BankName("bank"),
                        new BankAccountNumber("account number", Currency.of("USD")),
                        Money.of(5000, "USD")),
                ZonedDateTime.parse("2021-06-01T06:30:00Z")
        ));

        // when - adding paid OUTFLOW with paidDate in June (active period)
        CashFlowEvent.PaidCashChangeAppendedEvent validEvent = new CashFlowEvent.PaidCashChangeAppendedEvent(
                cashFlowId,
                cashChangeId,
                new Name("rent payment"),
                new Description("june rent"),
                Money.of(1500, "USD"),
                OUTFLOW,
                ZonedDateTime.parse("2021-06-15T06:30:00Z"),
                new CategoryName("Uncategorized"),
                ZonedDateTime.parse("2021-06-15T06:30:00Z"),
                ZonedDateTime.parse("2021-06-15T06:30:00Z")  // paidDate in June - correct!
        );

        cashFlow.apply(validEvent);

        // then - should succeed and decrease balance
        domainCashFlowRepository.save(cashFlow);

        CashFlowSnapshot snapshot = domainCashFlowRepository.findById(cashFlowId)
                .map(CashFlow::getSnapshot)
                .orElseThrow();

        // Balance should decrease: 5000 - 1500 = 3500
        assertThat(snapshot.bankAccount().balance()).isEqualTo(Money.of(3500, "USD"));
        assertThat(snapshot.cashChanges().get(cashChangeId).status()).isEqualTo(CONFIRMED);
    }
}
