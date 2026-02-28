package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.ContentReader;
import com.multi.vidulum.TestIds;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.CategoryOrigin;
import com.multi.vidulum.common.*;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Map;

import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CashFlowForecastProcessorTest extends IntegrationTest {

    @Test
    public void processInflows() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId firstCashChangeId = TestIds.nextCashChangeId();
        CashChangeId secondCashChangeId = TestIds.nextCashChangeId();

        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("name"),
                        new Description("description"),
                        BankAccount.fromIban(
                                "bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(0, "USD"),
                                null),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-06-15T06:30:00Z"),  // dueDate in same month as created
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        ZonedDateTime.parse("2021-06-15T16:30:00Z")
                ));

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2"),
                        new Description("cash change description 2"),
                        Money.of(70, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-15T06:30:00Z"),  // dueDate in same month as created
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.CashChangeEditedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2 edited"),
                        new Description("cash change description 2 edited"),
                        Money.of(120, "USD"),
                        new CategoryName("Uncategorized"),  // Keep same category
                        ZonedDateTime.parse("2021-07-12T00:00:00Z"),  // dueDate in same month
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        ZonedDateTime.parse("2021-07-10T16:30:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class, ZonedDateTime.class, CategoryOrigin.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/expected_inflow_processing.json")
                                .to(CashFlowForecastStatement.class));

    }

    @Test
    public void processOutflows() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId firstCashChangeId = TestIds.nextCashChangeId();
        CashChangeId secondCashChangeId = TestIds.nextCashChangeId();

        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("name"),
                        new Description("description"),
                        BankAccount.fromIban(
                                "bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(0, "USD"),
                                null),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(111, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-06-15T06:30:00Z"),  // dueDate in same month as created
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        ZonedDateTime.parse("2021-06-15T16:30:00Z")
                ));

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2"),
                        new Description("cash change description 2"),
                        Money.of(80, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-15T06:30:00Z"),  // dueDate in same month as created
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.CashChangeEditedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2 edited"),
                        new Description("cash change description 2 edited"),
                        Money.of(130, "USD"),
                        new CategoryName("Uncategorized"),  // Keep same category
                        ZonedDateTime.parse("2021-07-12T00:00:00Z"),  // dueDate in same month
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        ZonedDateTime.parse("2021-07-10T16:30:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class, ZonedDateTime.class, CategoryOrigin.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/expected_outflow_processing.json")
                                .to(CashFlowForecastStatement.class));

    }

    @Test
    public void shouldAttestMonth() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId firstCashChangeId = TestIds.nextCashChangeId();
        CashChangeId secondCashChangeId = TestIds.nextCashChangeId();
        CashChangeId thirdCashChangeId = TestIds.nextCashChangeId();
        CashChangeId forthCashChangeId = TestIds.nextCashChangeId();
        CashChangeId fifthCashChangeId = TestIds.nextCashChangeId();

        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("name"),
                        new Description("description"),
                        BankAccount.fromIban(
                                "bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(0, "USD"),
                                null),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),  // dueDate in same month - will be in July after attestation
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Special category"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"))
        );

        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Overhead costs"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"))
        );

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        forthCashChangeId,
                        new Name("cash change for new category 1"),
                        new Description("cash change description"),
                        Money.of(200, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Special category"),
                        ZonedDateTime.parse("2021-07-05T06:30:00Z"),  // dueDate in July (for attestation test)
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2"),
                        new Description("cash change description 2"),
                        Money.of(25, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-05T06:30:00Z"),  // dueDate in July (for attestation test)
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        fifthCashChangeId,
                        new Name("Cost 1"),
                        new Description("cash change description"),
                        Money.of(11, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Overhead costs"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),  // dueDate in June
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        ZonedDateTime.parse("2021-06-15T16:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        fifthCashChangeId,
                        ZonedDateTime.parse("2021-06-15T16:30:00Z")
                ));

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        thirdCashChangeId,
                        new Name("cash change name 3"),
                        new Description("cash change description 3"),
                        Money.of(70, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-03T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-08-15T06:30:00Z"),  // dueDate in August
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.MonthAttestedEvent(
                        cashFlowId,
                        YearMonth.parse("2021-07"),
                        Money.of(75, "USD"),
                        ZonedDateTime.parse("2021-07-15T06:30:00Z")
                )
        );

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.CashChangeEditedEvent(
                        cashFlowId,
                        thirdCashChangeId,
                        new Name("cash change name 3 edited"),
                        new Description("cash change description 3 edited"),
                        Money.of(120, "USD"),
                        new CategoryName("Uncategorized"),  // Keep same category
                        ZonedDateTime.parse("2021-08-12T00:00:00Z"),  // dueDate stays in August
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class, ZonedDateTime.class, CategoryOrigin.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/attestation_processing.json")
                                .to(CashFlowForecastStatement.class));
    }

    @Test
    public void shouldCreateNewCategory() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId firstCashChangeId = TestIds.nextCashChangeId();
        CashChangeId secondCashChangeId = TestIds.nextCashChangeId();

        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("name"),
                        new Description("description"),
                        BankAccount.fromIban(
                                "bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(0, "USD"),
                                null),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Special Category For Inflows"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Special Category For Outflows"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Special Category For Inflows"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),  // dueDate in same month as created
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name outflow"),
                        new Description("cash change description"),
                        Money.of(70, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Special Category For Outflows"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),  // dueDate in same month as created
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        ZonedDateTime.parse("2021-06-15T16:30:00Z")
                ));

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        ZonedDateTime.parse("2021-06-15T16:30:00Z")
                ));


        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class, ZonedDateTime.class, CategoryOrigin.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/new_category.json")
                                .to(CashFlowForecastStatement.class));
    }

    @Test
    public void shouldAppendCashChangeToSubCategory() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId firstCashChangeId = TestIds.nextCashChangeId();
        CashChangeId secondCashChangeId = TestIds.nextCashChangeId();
        CashChangeId thirdCashChangeId = TestIds.nextCashChangeId();

        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("name"),
                        new Description("description"),
                        BankAccount.fromIban(
                                "bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(0, "USD"),
                                null),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Overhead costs"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        new CategoryName("Overhead costs"),
                        new CategoryName("Bank fees"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Sales"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        new CategoryName("Sales"),
                        new CategoryName("Main product"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("Morgan Stanley fee"),
                        new Description("Morgan Stanley fee"),
                        Money.of(50, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Bank fees"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),  // dueDate in same month as created
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("Main product purchase"),
                        new Description("Main product purchase description"),
                        Money.of(79, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Main product"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),  // dueDate in same month as created
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        thirdCashChangeId,
                        new Name("Sales purchase"),
                        new Description("Main product purchase description"),
                        Money.of(10, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Sales"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),  // dueDate in same month as created
                        null // sourceRuleId
                ));

        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        ZonedDateTime.parse("2021-06-15T16:30:00Z")
                ));


        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        ZonedDateTime.parse("2021-06-15T16:30:00Z")
                ));

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        thirdCashChangeId,
                        ZonedDateTime.parse("2021-06-15T16:30:00Z")
                ));


        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class, ZonedDateTime.class, CategoryNode.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/append-cash-change-to-subcategory.json")
                                .to(CashFlowForecastStatement.class));
    }

    @Test
    public void processPaidInflows() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId paidCashChangeId1 = TestIds.nextCashChangeId();
        CashChangeId paidCashChangeId2 = TestIds.nextCashChangeId();

        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("name"),
                        new Description("description"),
                        BankAccount.fromIban(
                                "bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(1000, "USD"),
                                null),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.PaidCashChangeAppendedEvent(
                        cashFlowId,
                        paidCashChangeId1,
                        new Name("paid inflow 1"),
                        new Description("already paid inflow"),
                        Money.of(500, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-15T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-06-15T06:30:00Z"),
                        ZonedDateTime.parse("2021-06-15T06:30:00Z")
                ));

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.PaidCashChangeAppendedEvent(
                        cashFlowId,
                        paidCashChangeId2,
                        new Name("paid inflow 2"),
                        new Description("second paid inflow"),
                        Money.of(300, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-20T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-06-20T06:30:00Z"),
                        ZonedDateTime.parse("2021-06-20T06:30:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .satisfies(statement -> {
                    CashFlowMonthlyForecast juneForecast = statement.getForecasts().get(YearMonth.parse("2021-06"));
                    assertThat(juneForecast).isNotNull();

                    // Verify inflow stats - both transactions should be in actual (already paid)
                    CashSummary inflowStats = juneForecast.getCashFlowStats().getInflowStats();
                    assertThat(inflowStats.actual()).isEqualTo(Money.of(800, "USD"));

                    // Verify transactions are in PAID group
                    CashCategory uncategorizedInflow = juneForecast.findCategoryInflowsByCategoryName(new CategoryName("Uncategorized")).orElseThrow();
                    assertThat(uncategorizedInflow.getGroupedTransactions().get(PaymentStatus.PAID)).hasSize(2);
                    assertThat(uncategorizedInflow.getTotalPaidValue()).isEqualTo(Money.of(800, "USD"));
                });
    }

    @Test
    public void processPaidOutflows() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId paidCashChangeId1 = TestIds.nextCashChangeId();
        CashChangeId paidCashChangeId2 = TestIds.nextCashChangeId();

        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("name"),
                        new Description("description"),
                        BankAccount.fromIban(
                                "bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(2000, "USD"),
                                null),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.PaidCashChangeAppendedEvent(
                        cashFlowId,
                        paidCashChangeId1,
                        new Name("paid outflow 1"),
                        new Description("already paid outflow"),
                        Money.of(150, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z")
                ));

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.PaidCashChangeAppendedEvent(
                        cashFlowId,
                        paidCashChangeId2,
                        new Name("paid outflow 2"),
                        new Description("second paid outflow"),
                        Money.of(250, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-25T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-06-25T06:30:00Z"),
                        ZonedDateTime.parse("2021-06-25T06:30:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .satisfies(statement -> {
                    CashFlowMonthlyForecast juneForecast = statement.getForecasts().get(YearMonth.parse("2021-06"));
                    assertThat(juneForecast).isNotNull();

                    // Verify outflow stats - both transactions should be in actual (already paid)
                    CashSummary outflowStats = juneForecast.getCashFlowStats().getOutflowStats();
                    assertThat(outflowStats.actual()).isEqualTo(Money.of(400, "USD"));

                    // Verify transactions are in PAID group
                    CashCategory uncategorizedOutflow = juneForecast.findCategoryOutflowsByCategoryName(new CategoryName("Uncategorized")).orElseThrow();
                    assertThat(uncategorizedOutflow.getGroupedTransactions().get(PaymentStatus.PAID)).hasSize(2);
                    assertThat(uncategorizedOutflow.getTotalPaidValue()).isEqualTo(Money.of(400, "USD"));
                });
    }

    @Test
    public void processMixedExpectedAndPaidCashChanges() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId expectedCashChangeId = TestIds.nextCashChangeId();
        CashChangeId paidCashChangeId = TestIds.nextCashChangeId();

        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("name"),
                        new Description("description"),
                        BankAccount.fromIban(
                                "bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(1000, "USD"),
                                null),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        // Add expected cash change (PENDING)
        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        expectedCashChangeId,
                        new Name("expected inflow"),
                        new Description("pending inflow"),
                        Money.of(200, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-05T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-06-15T06:30:00Z"),
                        null // sourceRuleId
                ));

        // Add paid cash change (already CONFIRMED)
        Checksum lastEventChecksum = emit(
                new CashFlowEvent.PaidCashChangeAppendedEvent(
                        cashFlowId,
                        paidCashChangeId,
                        new Name("paid inflow"),
                        new Description("already paid inflow"),
                        Money.of(300, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z"),
                        ZonedDateTime.parse("2021-06-10T06:30:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .satisfies(statement -> {
                    CashFlowMonthlyForecast juneForecast = statement.getForecasts().get(YearMonth.parse("2021-06"));
                    assertThat(juneForecast).isNotNull();

                    // Verify inflow stats
                    CashSummary inflowStats = juneForecast.getCashFlowStats().getInflowStats();
                    // Paid should be in actual
                    assertThat(inflowStats.actual()).isEqualTo(Money.of(300, "USD"));
                    // Expected should be in expected
                    assertThat(inflowStats.expected()).isEqualTo(Money.of(200, "USD"));

                    // Verify transactions in correct groups
                    CashCategory uncategorizedInflow = juneForecast.findCategoryInflowsByCategoryName(new CategoryName("Uncategorized")).orElseThrow();
                    assertThat(uncategorizedInflow.getGroupedTransactions().get(PaymentStatus.PAID)).hasSize(1);
                    assertThat(uncategorizedInflow.getGroupedTransactions().get(PaymentStatus.EXPECTED)).hasSize(1);
                });
    }

    @Test
    public void processPaidCashChangeWithCustomCategory() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId paidCashChangeId = TestIds.nextCashChangeId();

        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("name"),
                        new Description("description"),
                        BankAccount.fromIban(
                                "bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(1000, "USD"),
                                null),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Salary"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.PaidCashChangeAppendedEvent(
                        cashFlowId,
                        paidCashChangeId,
                        new Name("Monthly Salary"),
                        new Description("June salary payment"),
                        Money.of(5000, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-25T06:30:00Z"),
                        new CategoryName("Salary"),
                        ZonedDateTime.parse("2021-06-25T06:30:00Z"),
                        ZonedDateTime.parse("2021-06-25T06:30:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .satisfies(statement -> {
                    CashFlowMonthlyForecast juneForecast = statement.getForecasts().get(YearMonth.parse("2021-06"));
                    assertThat(juneForecast).isNotNull();

                    // Verify the Salary category has the paid transaction
                    CashCategory salaryCategory = juneForecast.findCategoryInflowsByCategoryName(new CategoryName("Salary")).orElseThrow();
                    assertThat(salaryCategory.getGroupedTransactions().get(PaymentStatus.PAID)).hasSize(1);
                    assertThat(salaryCategory.getTotalPaidValue()).isEqualTo(Money.of(5000, "USD"));

                    // Verify inflow stats
                    CashSummary inflowStats = juneForecast.getCashFlowStats().getInflowStats();
                    assertThat(inflowStats.actual()).isEqualTo(Money.of(5000, "USD"));
                });
    }

    @Test
    public void shouldRollbackImportAndClearTransactionsFromImportPendingMonths() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId historicalCashChangeId1 = TestIds.nextCashChangeId();
        CashChangeId historicalCashChangeId2 = TestIds.nextCashChangeId();

        // Create CashFlow with history (SETUP mode)
        emit(
                new CashFlowEvent.CashFlowWithHistoryCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("Test CashFlow"),
                        new Description("CashFlow for rollback test"),
                        BankAccount.fromIban(
                                "Test Bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(1000, "USD"),
                                null),
                        YearMonth.parse("2021-01"),
                        YearMonth.parse("2021-06"),
                        Money.of(1000, "USD"),
                        ZonedDateTime.parse("2021-06-15T12:00:00Z")
                )
        );

        // Import historical transactions
        emit(
                new CashFlowEvent.HistoricalCashChangeImportedEvent(
                        cashFlowId,
                        historicalCashChangeId1,
                        new Name("Historical inflow"),
                        new Description("Historical payment"),
                        Money.of(500, "USD"),
                        INFLOW,
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-03-15T10:00:00Z"),
                        ZonedDateTime.parse("2021-03-15T10:00:00Z"),
                        ZonedDateTime.parse("2021-06-15T12:00:00Z")
                ));

        emit(
                new CashFlowEvent.HistoricalCashChangeImportedEvent(
                        cashFlowId,
                        historicalCashChangeId2,
                        new Name("Historical outflow"),
                        new Description("Historical expense"),
                        Money.of(200, "USD"),
                        OUTFLOW,
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-04-20T10:00:00Z"),
                        ZonedDateTime.parse("2021-04-20T10:00:00Z"),
                        ZonedDateTime.parse("2021-06-15T12:00:00Z")
                ));

        // Rollback the import (clear transactions but keep categories)
        Checksum lastEventChecksum = emit(
                new CashFlowEvent.ImportRolledBackEvent(
                        cashFlowId,
                        2,  // deletedTransactionsCount
                        0,  // deletedCategoriesCount
                        false,  // categoriesDeleted
                        ZonedDateTime.parse("2021-06-15T12:30:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .satisfies(statement -> {
                    // Verify IMPORT_PENDING months have no transactions
                    for (CashFlowMonthlyForecast forecast : statement.getForecasts().values()) {
                        if (forecast.getStatus() == CashFlowMonthlyForecast.Status.IMPORT_PENDING) {
                            // Stats should be reset to zero
                            assertThat(forecast.getCashFlowStats().getInflowStats().actual()).isEqualTo(Money.zero("USD"));
                            assertThat(forecast.getCashFlowStats().getOutflowStats().actual()).isEqualTo(Money.zero("USD"));

                            // Transactions should be cleared (all lists empty)
                            for (CashCategory category : forecast.getCategorizedInFlows()) {
                                int totalTransactions = category.getGroupedTransactions().getTransactions().values().stream()
                                        .mapToInt(java.util.List::size).sum();
                                assertThat(totalTransactions).isZero();
                            }
                            for (CashCategory category : forecast.getCategorizedOutFlows()) {
                                int totalTransactions = category.getGroupedTransactions().getTransactions().values().stream()
                                        .mapToInt(java.util.List::size).sum();
                                assertThat(totalTransactions).isZero();
                            }
                        }
                    }
                });
    }

    @Test
    public void shouldRollbackImportAndClearCategoriesWhenRequested() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId historicalCashChangeId = TestIds.nextCashChangeId();

        // Create CashFlow with history
        emit(
                new CashFlowEvent.CashFlowWithHistoryCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("Test CashFlow"),
                        new Description("CashFlow for category rollback test"),
                        BankAccount.fromIban(
                                "Test Bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(1000, "USD"),
                                null),
                        YearMonth.parse("2021-01"),
                        YearMonth.parse("2021-06"),
                        Money.of(1000, "USD"),
                        ZonedDateTime.parse("2021-06-15T12:00:00Z")
                )
        );

        // Create custom category
        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Salary"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-15T12:00:00Z")
                )
        );

        // Import transaction to custom category
        emit(
                new CashFlowEvent.HistoricalCashChangeImportedEvent(
                        cashFlowId,
                        historicalCashChangeId,
                        new Name("Salary payment"),
                        new Description("Monthly salary"),
                        Money.of(5000, "USD"),
                        INFLOW,
                        new CategoryName("Salary"),
                        ZonedDateTime.parse("2021-03-15T10:00:00Z"),
                        ZonedDateTime.parse("2021-03-15T10:00:00Z"),
                        ZonedDateTime.parse("2021-06-15T12:00:00Z")
                ));

        // Rollback with category deletion
        Checksum lastEventChecksum = emit(
                new CashFlowEvent.ImportRolledBackEvent(
                        cashFlowId,
                        1,  // deletedTransactionsCount
                        1,  // deletedCategoriesCount
                        true,  // categoriesDeleted
                        ZonedDateTime.parse("2021-06-15T12:30:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .satisfies(statement -> {
                    // Verify category structure is reset to just Uncategorized
                    assertThat(statement.getCategoryStructure().inflowCategoryStructure())
                            .hasSize(1)
                            .extracting(CategoryNode::getCategoryName)
                            .containsExactly(new CategoryName("Uncategorized"));

                    assertThat(statement.getCategoryStructure().outflowCategoryStructure())
                            .hasSize(1)
                            .extracting(CategoryNode::getCategoryName)
                            .containsExactly(new CategoryName("Uncategorized"));

                    // Verify IMPORT_PENDING months have only Uncategorized category
                    for (CashFlowMonthlyForecast forecast : statement.getForecasts().values()) {
                        if (forecast.getStatus() == CashFlowMonthlyForecast.Status.IMPORT_PENDING) {
                            assertThat(forecast.getCategorizedInFlows())
                                    .hasSize(1)
                                    .extracting(cat -> cat.getCategoryName().name())
                                    .containsExactly("Uncategorized");

                            assertThat(forecast.getCategorizedOutFlows())
                                    .hasSize(1)
                                    .extracting(cat -> cat.getCategoryName().name())
                                    .containsExactly("Uncategorized");
                        }
                    }
                });
    }

    @Test
    public void shouldAllowReImportAfterRollback() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId historicalCashChangeId1 = TestIds.nextCashChangeId();
        CashChangeId historicalCashChangeId2 = TestIds.nextCashChangeId();

        // Create CashFlow with history
        emit(
                new CashFlowEvent.CashFlowWithHistoryCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("Test CashFlow"),
                        new Description("CashFlow for re-import test"),
                        BankAccount.fromIban(
                                "Test Bank",
                                "GB29NWBK60161331926819",
                                Currency.of("USD"),
                                Money.of(1000, "USD"),
                                null),
                        YearMonth.parse("2021-01"),
                        YearMonth.parse("2021-06"),
                        Money.of(1000, "USD"),
                        ZonedDateTime.parse("2021-06-15T12:00:00Z")
                )
        );

        // First import
        emit(
                new CashFlowEvent.HistoricalCashChangeImportedEvent(
                        cashFlowId,
                        historicalCashChangeId1,
                        new Name("Wrong import"),
                        new Description("This will be rolled back"),
                        Money.of(100, "USD"),
                        INFLOW,
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-03-15T10:00:00Z"),
                        ZonedDateTime.parse("2021-03-15T10:00:00Z"),
                        ZonedDateTime.parse("2021-06-15T12:00:00Z")
                ));

        // Rollback
        emit(
                new CashFlowEvent.ImportRolledBackEvent(
                        cashFlowId,
                        1,
                        0,
                        false,
                        ZonedDateTime.parse("2021-06-15T12:30:00Z")
                ));

        // Re-import with correct data
        Checksum lastEventChecksum = emit(
                new CashFlowEvent.HistoricalCashChangeImportedEvent(
                        cashFlowId,
                        historicalCashChangeId2,
                        new Name("Correct import"),
                        new Description("This is the correct data"),
                        Money.of(750, "USD"),
                        INFLOW,
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-03-20T10:00:00Z"),
                        ZonedDateTime.parse("2021-03-20T10:00:00Z"),
                        ZonedDateTime.parse("2021-06-15T12:35:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .satisfies(statement -> {
                    CashFlowMonthlyForecast marchForecast = statement.getForecasts().get(YearMonth.parse("2021-03"));
                    assertThat(marchForecast).isNotNull();

                    // Only the re-imported transaction should exist
                    CashCategory uncategorizedInflow = marchForecast.findCategoryInflowsByCategoryName(new CategoryName("Uncategorized")).orElseThrow();
                    assertThat(uncategorizedInflow.getGroupedTransactions().get(PaymentStatus.PAID)).hasSize(1);
                    assertThat(uncategorizedInflow.getTotalPaidValue()).isEqualTo(Money.of(750, "USD"));
                });
    }

    /**
     * Test that expected cash changes are assigned to the correct month based on dueDate,
     * not created date. This is critical for Recurring Rules which create multiple
     * transactions at once with different dueDates.
     */
    @Test
    public void shouldAssignExpectedCashChangesToCorrectMonthBasedOnDueDate() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId marchCashChangeId = TestIds.nextCashChangeId();
        CashChangeId aprilCashChangeId = TestIds.nextCashChangeId();
        CashChangeId mayCashChangeId = TestIds.nextCashChangeId();

        // All events created in February, but with different dueDates
        ZonedDateTime createdDate = ZonedDateTime.parse("2026-02-15T10:00:00Z");

        // Create CashFlow
        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        new UserId("U10000001"),
                        new Name("Recurring Rules Test"),
                        new Description("Testing dueDate-based month assignment"),
                        BankAccount.fromIban(
                                "Test Bank",
                                "GB29NWBK60161331926819",
                                Currency.of("PLN"),
                                Money.of(10000, "PLN"),
                                null),
                        createdDate
                )
        );

        // Create category for the transactions
        emit(
                new CashFlowEvent.CategoryCreatedEvent(
                        cashFlowId,
                        CategoryName.NOT_DEFINED,
                        new CategoryName("Salary"),
                        INFLOW,
                        createdDate
                )
        );

        // Simulate Recurring Rule creating 3 expected cash changes at once
        // All created in February, but due in March, April, May

        // Transaction due in March
        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        marchCashChangeId,
                        new Name("March Salary"),
                        new Description("Monthly salary"),
                        Money.of(8000, "PLN"),
                        INFLOW,
                        createdDate,  // created in February
                        new CategoryName("Salary"),
                        ZonedDateTime.parse("2026-03-05T00:00:00Z"),  // due in March
                        null
                ));

        // Transaction due in April
        emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        aprilCashChangeId,
                        new Name("April Salary"),
                        new Description("Monthly salary"),
                        Money.of(8000, "PLN"),
                        INFLOW,
                        createdDate,  // created in February
                        new CategoryName("Salary"),
                        ZonedDateTime.parse("2026-04-05T00:00:00Z"),  // due in April
                        null
                ));

        // Transaction due in May
        Checksum lastEventChecksum = emit(
                new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                        cashFlowId,
                        mayCashChangeId,
                        new Name("May Salary"),
                        new Description("Monthly salary"),
                        Money.of(8000, "PLN"),
                        INFLOW,
                        createdDate,  // created in February
                        new CategoryName("Salary"),
                        ZonedDateTime.parse("2026-05-05T00:00:00Z"),  // due in May
                        null
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .satisfies(statement -> {
                    // February should have NO expected transactions (only created date was in Feb)
                    CashFlowMonthlyForecast februaryForecast = statement.getForecasts().get(YearMonth.parse("2026-02"));
                    if (februaryForecast != null) {
                        CashCategory febSalary = februaryForecast.findCategoryInflowsByCategoryName(new CategoryName("Salary")).orElse(null);
                        if (febSalary != null) {
                            assertThat(febSalary.getGroupedTransactions().get(PaymentStatus.EXPECTED))
                                    .as("February should have NO expected transactions - they should be in their dueDate months")
                                    .isEmpty();
                        }
                    }

                    // March should have the March transaction
                    CashFlowMonthlyForecast marchForecast = statement.getForecasts().get(YearMonth.parse("2026-03"));
                    assertThat(marchForecast)
                            .as("March forecast should exist")
                            .isNotNull();
                    CashCategory marchSalary = marchForecast.findCategoryInflowsByCategoryName(new CategoryName("Salary")).orElseThrow();
                    assertThat(marchSalary.getGroupedTransactions().get(PaymentStatus.EXPECTED))
                            .as("March should have exactly 1 expected transaction")
                            .hasSize(1);
                    assertThat(marchSalary.getGroupedTransactions().get(PaymentStatus.EXPECTED).get(0).getName().name())
                            .isEqualTo("March Salary");

                    // April should have the April transaction
                    CashFlowMonthlyForecast aprilForecast = statement.getForecasts().get(YearMonth.parse("2026-04"));
                    assertThat(aprilForecast)
                            .as("April forecast should exist")
                            .isNotNull();
                    CashCategory aprilSalary = aprilForecast.findCategoryInflowsByCategoryName(new CategoryName("Salary")).orElseThrow();
                    assertThat(aprilSalary.getGroupedTransactions().get(PaymentStatus.EXPECTED))
                            .as("April should have exactly 1 expected transaction")
                            .hasSize(1);
                    assertThat(aprilSalary.getGroupedTransactions().get(PaymentStatus.EXPECTED).get(0).getName().name())
                            .isEqualTo("April Salary");

                    // May should have the May transaction
                    CashFlowMonthlyForecast mayForecast = statement.getForecasts().get(YearMonth.parse("2026-05"));
                    assertThat(mayForecast)
                            .as("May forecast should exist")
                            .isNotNull();
                    CashCategory maySalary = mayForecast.findCategoryInflowsByCategoryName(new CategoryName("Salary")).orElseThrow();
                    assertThat(maySalary.getGroupedTransactions().get(PaymentStatus.EXPECTED))
                            .as("May should have exactly 1 expected transaction")
                            .hasSize(1);
                    assertThat(maySalary.getGroupedTransactions().get(PaymentStatus.EXPECTED).get(0).getName().name())
                            .isEqualTo("May Salary");
                });
    }

    private Checksum emit(CashFlowEvent cashFlowEvent) {
        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", cashFlowEvent.getClass().getSimpleName()))
                        .content(JsonContent.asPrettyJson(cashFlowEvent))
                        .build());
        return new Checksum(
                DigestUtils.md5DigestAsHex(
                        JsonContent.asJson(cashFlowEvent)
                                .content()
                                .getBytes(StandardCharsets.UTF_8)));
    }
}
