package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZonedDateTime;
import java.util.Map;

import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static org.assertj.core.api.Assertions.assertThat;

class CashFlowForecastProcessorTest extends IntegrationTest {

    @Autowired
    private CashFlowForecastStatementRepository statementRepository;

    @Autowired
    private CashFlowEventEmitter cashFlowEventEmitter;

    @Test
    public void test() throws InterruptedException {
        CashFlowId cashFlowId = CashFlowId.generate();
        CashChangeId firstCashChangeId = CashChangeId.generate();
        CashChangeId secondCashChangeId = CashChangeId.generate();

        emit(
                new CashFlowEvent.CashFlowCreatedEvent(
                        cashFlowId,
                        UserId.of("user"),
                        new Name("name"),
                        new Description("description"),
                        Money.of(0, "USD"),
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

        emit(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        cashFlowId,
                        secondCashChangeId,
                        ZonedDateTime.parse("2021-08-10T16:30:00Z")
                ));

        Thread.sleep(15000);
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(cashFlowId).get();
        JsonContent jsonContent = JsonContent.asJson(statement);
        String content = jsonContent.content();
        assertThat(statementRepository.findByCashFlowId(cashFlowId))
                .isPresent()
                .get()
                .isEqualTo(null);

    }

    private void emit(CashFlowEvent cashFlowEvent) {
        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", cashFlowEvent.getClass().getSimpleName()))
                        .content(JsonContent.asJson(cashFlowEvent))
                        .build());
    }

}