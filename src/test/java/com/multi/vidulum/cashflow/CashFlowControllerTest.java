package com.multi.vidulum.cashflow;

import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.app.CashFlowRestController;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.*;
import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static org.assertj.core.api.Assertions.assertThat;

public class CashFlowControllerTest extends IntegrationTest {

    @Autowired
    private CashFlowRestController cashFlowRestController;

    @Test
    void shouldCreateAndGetCashFlow() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .balance(Money.zero("USD"))
                        .build()
        );

        // when and then
        assertThat(cashFlowRestController.getCashFlow(cashFlowId)).isEqualTo(
                CashFlowDto.CashFlowSummaryJson.builder()
                        .cashFlowId(cashFlowId)
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .balance(Money.zero("USD"))
                        .status(CashFlow.CashFlowStatus.OPEN)
                        .cashChanges(Map.of())
                        .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .lastModification(null)
                        .build()
        );

        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.CashFlowCreatedEvent.class.getSimpleName()))
                        .orElse(false));
    }

    @Test
    void shouldAppendCashChange() {
        // when
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .balance(Money.zero("USD"))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .name("cash-change name")
                        .description("cash-change description")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );

        // then
        assertThat(cashFlowRestController.getCashFlow(cashFlowId)).isEqualTo(
                CashFlowDto.CashFlowSummaryJson.builder()
                        .cashFlowId(cashFlowId)
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .balance(Money.zero("USD"))
                        .status(CashFlow.CashFlowStatus.OPEN)
                        .cashChanges(Map.of(
                                cashChangeId,
                                CashFlowDto.CashChangeSummaryJson.builder()
                                        .cashChangeId(cashChangeId)
                                        .name("cash-change name")
                                        .description("cash-change description")
                                        .money(Money.of(100, "USD"))
                                        .type(INFLOW)
                                        .status(PENDING)
                                        .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                                        .endDate(null)
                                        .build()

                        ))
                        .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .lastModification(null)
                        .build()
        );

        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .containsAll(
                                        List.of(
                                                CashFlowEvent.CashFlowCreatedEvent.class.getSimpleName(),
                                                CashFlowEvent.CashChangeAppendedEvent.class.getSimpleName()
                                        ))).orElse(false));
    }

    @Test
    void shouldConfirmCashChange() {
        // when
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .balance(Money.zero("USD"))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .name("cash-change name")
                        .description("cash-change description")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );

        cashFlowRestController.confirm(
                CashFlowDto.ConfirmCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .cashChangeId(cashChangeId)
                        .build()
        );

        // then
        assertThat(cashFlowRestController.getCashFlow(cashFlowId)).isEqualTo(
                CashFlowDto.CashFlowSummaryJson.builder()
                        .cashFlowId(cashFlowId)
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .balance(Money.of(100, "USD"))
                        .status(CashFlow.CashFlowStatus.OPEN)
                        .cashChanges(Map.of(
                                cashChangeId,
                                CashFlowDto.CashChangeSummaryJson.builder()
                                        .cashChangeId(cashChangeId)
                                        .name("cash-change name")
                                        .description("cash-change description")
                                        .money(Money.of(100, "USD"))
                                        .type(INFLOW)
                                        .status(CONFIRMED)
                                        .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                                        .endDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                        .build()
                        ))
                        .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .lastModification(null)
                        .build()
        );

        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .containsAll(
                                        List.of(
                                                CashFlowEvent.CashFlowCreatedEvent.class.getSimpleName(),
                                                CashFlowEvent.CashChangeAppendedEvent.class.getSimpleName(),
                                                CashFlowEvent.CashChangeConfirmedEvent.class.getSimpleName()
                                        ))).orElse(false));
    }

    @Test
    void shouldEditCashChange() {
        // when
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .balance(Money.zero("USD"))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .name("cash-change name")
                        .description("cash-change description")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );

        cashFlowRestController.edit(
                CashFlowDto.EditCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .cashChangeId(cashChangeId)
                        .name("cash-change name edited")
                        .description("cash-change description edited")
                        .money(Money.of(200, "USD"))
                        .dueDate(ZonedDateTime.parse("2024-01-11T00:00:00Z"))
                        .build()
        );

        // then
        assertThat(cashFlowRestController.getCashFlow(cashFlowId)).isEqualTo(
                CashFlowDto.CashFlowSummaryJson.builder()
                        .cashFlowId(cashFlowId)
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .balance(Money.zero("USD"))
                        .status(CashFlow.CashFlowStatus.OPEN)
                        .cashChanges(Map.of(
                                cashChangeId,
                                CashFlowDto.CashChangeSummaryJson.builder()
                                        .cashChangeId(cashChangeId)
                                        .name("cash-change name edited")
                                        .description("cash-change description edited")
                                        .money(Money.of(200, "USD"))
                                        .type(INFLOW)
                                        .status(PENDING)
                                        .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                        .dueDate(ZonedDateTime.parse("2024-01-11T00:00:00Z"))
                                        .endDate(null)
                                        .build()

                        ))
                        .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .lastModification(null)
                        .build()
        );

        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .containsAll(
                                        List.of(
                                                CashFlowEvent.CashFlowCreatedEvent.class.getSimpleName(),
                                                CashFlowEvent.CashChangeAppendedEvent.class.getSimpleName(),
                                                CashFlowEvent.CashChangeEditedEvent.class.getSimpleName()
                                        ))).orElse(false));
    }

    @Test
    void shouldRejectCashChange() {
        // when
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .balance(Money.zero("USD"))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .name("cash-change name")
                        .description("cash-change description")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );

        cashFlowRestController.reject(
                CashFlowDto.RejectCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .cashChangeId(cashChangeId)
                        .reason("some reason")
                        .build()
        );

        // then
        assertThat(cashFlowRestController.getCashFlow(cashFlowId)).isEqualTo(
                CashFlowDto.CashFlowSummaryJson.builder()
                        .cashFlowId(cashFlowId)
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .balance(Money.zero("USD"))
                        .status(CashFlow.CashFlowStatus.OPEN)
                        .cashChanges(Map.of(
                                cashChangeId,
                                CashFlowDto.CashChangeSummaryJson.builder()
                                        .cashChangeId(cashChangeId)
                                        .name("cash-change name")
                                        .description("cash-change description")
                                        .money(Money.of(100, "USD"))
                                        .type(INFLOW)
                                        .status(REJECTED)
                                        .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                                        .endDate(null)
                                        .build()

                        ))
                        .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .lastModification(null)
                        .build()
        );

        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .containsAll(
                                        List.of(
                                                CashFlowEvent.CashFlowCreatedEvent.class.getSimpleName(),
                                                CashFlowEvent.CashChangeAppendedEvent.class.getSimpleName(),
                                                CashFlowEvent.CashChangeRejectedEvent.class.getSimpleName()
                                        ))).orElse(false));

    }
}
