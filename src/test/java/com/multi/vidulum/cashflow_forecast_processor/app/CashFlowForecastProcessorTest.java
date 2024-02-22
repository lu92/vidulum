package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.ContentReader;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

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
                                new AccountNumber("account number"),
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
                        ZonedDateTime.parse("2021-08-15T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeEditedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2 edited"),
                        new Description("cash change description 2 edited"),
                        Money.of(120, "USD"),
                        ZonedDateTime.parse("2021-08-12T00:00:00Z")
                )
        );

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        ZonedDateTime.parse("2021-08-10T16:30:00Z")
                ));

        await()
                .until(() -> statementRepository.findByCashFlowId(cashFlowId)
                        .map(statement -> statement.getLastMessageChecksum().equals(lastEventChecksum))
                        .orElse(false));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class)
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
                                new AccountNumber("account number"),
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
                        ZonedDateTime.parse("2021-08-15T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeEditedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2 edited"),
                        new Description("cash change description 2 edited"),
                        Money.of(130, "USD"),
                        ZonedDateTime.parse("2021-08-12T00:00:00Z")
                )
        );

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        ZonedDateTime.parse("2021-08-10T16:30:00Z")
                ));

        await()
                .until(() -> statementRepository.findByCashFlowId(cashFlowId)
                        .map(statement -> statement.getLastMessageChecksum().equals(lastEventChecksum))
                        .orElse(false));

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/expected_outflow_processing.json")
                                .to(CashFlowForecastStatement.class));

    }

    @Test
    public void shouldAttestedMonth() {
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
                                new AccountNumber("account number"),
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
                        ZonedDateTime.parse("2021-07-01T06:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        firstCashChangeId,
                        ZonedDateTime.parse("2021-06-15T16:30:00Z")
                ));

        emit(
                new CashFlowEvent.CashChangeAppendedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        new Name("cash change name 2"),
                        new Description("cash change description 2"),
                        Money.of(70, "USD"),
                        INFLOW,
                        ZonedDateTime.parse("2021-06-03T06:30:00Z"),
                        ZonedDateTime.parse("2021-08-15T06:30:00Z")
                ));

        Checksum lastEventChecksum = emit(
                new CashFlowEvent.MonthAttestedEvent(
                        cashFlowId,
                        YearMonth.parse("2021-06"),
                        Money.of(777, "USD"),
                        ZonedDateTime.parse("2021-06-15T06:30:00Z")
                        )
        );

//        Checksum lastEventChecksum = emit(
//                new CashFlowEvent.CashChangeEditedEvent(
//                        cashFlowId,
//                        secondCashChangeId,
//                        new Name("cash change name 2 edited"),
//                        new Description("cash change description 2 edited"),
//                        Money.of(120, "USD"),
//                        ZonedDateTime.parse("2021-08-12T00:00:00Z")
//                )
//        );

        await()
                .until(() -> statementRepository.findByCashFlowId(cashFlowId)
                        .map(statement -> statement.getLastMessageChecksum().equals(lastEventChecksum))
                        .orElse(false));

        Optional<CashFlowForecastStatement> byCashFlowId = statementRepository.findByCashFlowId(cashFlowId);
        System.out.println();

        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .usingRecursiveComparison()
                .ignoringFieldsOfTypes(CashFlowId.class, CashChangeId.class, Checksum.class)
                .isEqualTo(
                        ContentReader.load("cashflow_forecast_processor/attestation_processing.json")
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
