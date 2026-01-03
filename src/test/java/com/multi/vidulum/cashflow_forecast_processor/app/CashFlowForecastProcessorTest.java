package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.ContentReader;
import com.multi.vidulum.cashflow.domain.*;
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
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId firstCashChangeId = CashChangeId.generate();
        CashChangeId secondCashChangeId = CashChangeId.generate();

        emit(
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

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        ZonedDateTime.parse("2021-07-15T16:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2"),
                        new Description("cash change description 2"),
                        Money.of(70, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-08-15T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeEditedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2 edited"),
                        new Description("cash change description 2 edited"),
                        Money.of(120, "USD"),
                        ZonedDateTime.parse("2021-08-12T00:00:00Z"),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        ZonedDateTime.parse("2021-08-10T16:30:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class, ZonedDateTime.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/expected_inflow_processing.json")
                                .to(CashFlowForecastStatement.class));

    }

    @Test
    public void processOutflows() {
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId firstCashChangeId = CashChangeId.generate();
        CashChangeId secondCashChangeId = CashChangeId.generate();

        emit(
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

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(111, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        ZonedDateTime.parse("2021-07-15T16:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2"),
                        new Description("cash change description 2"),
                        Money.of(80, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-07-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-08-15T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeEditedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2 edited"),
                        new Description("cash change description 2 edited"),
                        Money.of(130, "USD"),
                        ZonedDateTime.parse("2021-08-12T00:00:00Z"),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        ZonedDateTime.parse("2021-08-10T16:30:00Z")
                ));

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class, ZonedDateTime.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/expected_outflow_processing.json")
                                .to(CashFlowForecastStatement.class));

    }

    @Test
    public void shouldAttestMonth() {
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId firstCashChangeId = CashChangeId.generate();
        CashChangeId secondCashChangeId = CashChangeId.generate();
        CashChangeId thirdCashChangeId = CashChangeId.generate();
        CashChangeId forthCashChangeId = CashChangeId.generate();
        CashChangeId fifthCashChangeId = CashChangeId.generate();

        emit(
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

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
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
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        forthCashChangeId,
                        new Name("cash change for new category 1"),
                        new Description("cash change description"),
                        Money.of(200, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Special category"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2"),
                        new Description("cash change description 2"),
                        Money.of(25, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        fifthCashChangeId,
                        new Name("Cost 1"),
                        new Description("cash change description"),
                        Money.of(11, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Overhead costs"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
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
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        thirdCashChangeId,
                        new Name("cash change name 3"),
                        new Description("cash change description 3"),
                        Money.of(70, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-03T06:30:00Z"),
                        new CategoryName("Uncategorized"),
                        ZonedDateTime.parse("2021-08-15T06:30:00Z")
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
                        ZonedDateTime.parse("2021-08-12T00:00:00Z"),
                        ZonedDateTime.parse("2021-06-01T06:30:00Z")
                )
        );

        await().until(() -> lastEventIsProcessed(cashFlowId, lastEventChecksum));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class, ZonedDateTime.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/attestation_processing.json")
                                .to(CashFlowForecastStatement.class));
    }

    @Test
    public void shouldCreateNewCategory() {
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId firstCashChangeId = CashChangeId.generate();
        CashChangeId secondCashChangeId = CashChangeId.generate();

        emit(
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
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("cash change name"),
                        new Description("cash change description"),
                        Money.of(100, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Special Category For Inflows"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name outflow"),
                        new Description("cash change description"),
                        Money.of(70, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Special Category For Outflows"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
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
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class, ZonedDateTime.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/new_category.json")
                                .to(CashFlowForecastStatement.class));
    }

    @Test
    public void shouldAppendCashChangeToSubCategory() {
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId firstCashChangeId = CashChangeId.generate();
        CashChangeId secondCashChangeId = CashChangeId.generate();
        CashChangeId thirdCashChangeId = CashChangeId.generate();

        emit(
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
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        new Name("Morgan Stanley fee"),
                        new Description("Morgan Stanley fee"),
                        Money.of(50, "USD"),
                        OUTFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Bank fees"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("Main product purchase"),
                        new Description("Main product purchase description"),
                        Money.of(79, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Main product"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        thirdCashChangeId,
                        new Name("Sales purchase"),
                        new Description("Main product purchase description"),
                        Money.of(10, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-01T06:30:00Z"),
                        new CategoryName("Sales"),
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
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
