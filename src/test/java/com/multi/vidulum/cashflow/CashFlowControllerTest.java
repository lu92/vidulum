package com.multi.vidulum.cashflow;

import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.app.CashFlowRestController;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow_forecast_processor.app.CashCategory;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowMonthlyForecast;
import com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.*;
import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        // when and then
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);

        // verify lastMessageChecksum is set (contains MD5 hash of last event)
        assertThat(result.getLastMessageChecksum()).isNotNull();

        assertThat(result)
                .usingRecursiveComparison()
                .ignoringFields("lastMessageChecksum")
                .isEqualTo(
                        CashFlowDto.CashFlowSummaryJson.builder()
                                .cashFlowId(cashFlowId)
                                .userId("userId")
                                .name("cash-flow name")
                                .description("cash-flow description")
                                .bankAccount(new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(0, "USD")))
                                .status(CashFlow.CashFlowStatus.OPEN)
                                .cashChanges(Map.of())
                                .inflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .outflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
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
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        CashFlowDto.CashFlowSummaryJson cashFlowSummaryJson = cashFlowRestController.getCashFlow(cashFlowId);

        String cashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("cash-change name")
                        .description("cash-change description")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );

        // then
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);

        // verify lastMessageChecksum is set (contains MD5 hash of last event)
        assertThat(result.getLastMessageChecksum()).isNotNull();

        assertThat(result)
                .usingRecursiveComparison()
                .ignoringFields("lastMessageChecksum")
                .isEqualTo(
                        CashFlowDto.CashFlowSummaryJson.builder()
                                .cashFlowId(cashFlowId)
                                .userId("userId")
                                .name("cash-flow name")
                                .description("cash-flow description")
                                .bankAccount(new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(0, "USD")))
                                .status(CashFlow.CashFlowStatus.OPEN)
                                .cashChanges(Map.of(
                                        cashChangeId,
                                        CashFlowDto.CashChangeSummaryJson.builder()
                                                .cashChangeId(cashChangeId)
                                                .name("cash-change name")
                                                .description("cash-change description")
                                                .money(Money.of(100, "USD"))
                                                .type(INFLOW)
                                                .categoryName("Uncategorized")
                                                .status(PENDING)
                                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                                .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                                                .endDate(null)
                                                .build()

                                ))
                                .inflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .outflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                .lastModification(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
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
                                                CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName()
                                        ))).orElse(false));
    }

    @Test
    void shouldAppendPaidCashChange() {
        // when
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("paid cash-change name")
                        .description("paid cash-change description")
                        .money(Money.of(150, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .build()
        );

        // then
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);

        // verify lastMessageChecksum is set (contains MD5 hash of last event)
        assertThat(result.getLastMessageChecksum()).isNotNull();

        // Cash change should be CONFIRMED immediately and balance should be updated
        assertThat(result)
                .usingRecursiveComparison()
                .ignoringFields("lastMessageChecksum")
                .isEqualTo(
                        CashFlowDto.CashFlowSummaryJson.builder()
                                .cashFlowId(cashFlowId)
                                .userId("userId")
                                .name("cash-flow name")
                                .description("cash-flow description")
                                .bankAccount(new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(150, "USD"))) // Balance updated immediately
                                .status(CashFlow.CashFlowStatus.OPEN)
                                .cashChanges(Map.of(
                                        cashChangeId,
                                        CashFlowDto.CashChangeSummaryJson.builder()
                                                .cashChangeId(cashChangeId)
                                                .name("paid cash-change name")
                                                .description("paid cash-change description")
                                                .money(Money.of(150, "USD"))
                                                .type(INFLOW)
                                                .categoryName("Uncategorized")
                                                .status(CONFIRMED) // Status is CONFIRMED immediately
                                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                                .dueDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                                .endDate(ZonedDateTime.parse("2022-01-01T00:00:00Z")) // endDate is set to paidDate
                                                .build()
                                ))
                                .inflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .outflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                .lastModification(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
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
                                                CashFlowEvent.PaidCashChangeAppendedEvent.class.getSimpleName()
                                        ))).orElse(false));
    }

    @Test
    void shouldAppendPaidCashChangeForOutflow() {
        // when
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(500, "USD")))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("grocery shopping")
                        .description("weekly groceries")
                        .money(Money.of(75, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .build()
        );

        // then
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);

        // Balance should be decreased by outflow amount
        assertThat(result.getBankAccount().balance()).isEqualTo(Money.of(425, "USD"));
        assertThat(result.getCashChanges().get(cashChangeId).getStatus()).isEqualTo(CONFIRMED);
        assertThat(result.getCashChanges().get(cashChangeId).getType()).isEqualTo(OUTFLOW);

        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .containsAll(
                                        List.of(
                                                CashFlowEvent.CashFlowCreatedEvent.class.getSimpleName(),
                                                CashFlowEvent.PaidCashChangeAppendedEvent.class.getSimpleName()
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
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
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
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);

        // verify lastMessageChecksum is set (contains MD5 hash of last event)
        assertThat(result.getLastMessageChecksum()).isNotNull();

        assertThat(result)
                .usingRecursiveComparison()
                .ignoringFields("lastMessageChecksum")
                .isEqualTo(
                        CashFlowDto.CashFlowSummaryJson.builder()
                                .cashFlowId(cashFlowId)
                                .userId("userId")
                                .name("cash-flow name")
                                .description("cash-flow description")
                                .bankAccount(new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(100, "USD")))
                                .status(CashFlow.CashFlowStatus.OPEN)
                                .cashChanges(Map.of(
                                        cashChangeId,
                                        CashFlowDto.CashChangeSummaryJson.builder()
                                                .cashChangeId(cashChangeId)
                                                .name("cash-change name")
                                                .description("cash-change description")
                                                .money(Money.of(100, "USD"))
                                                .type(INFLOW)
                                                .categoryName("Uncategorized")
                                                .status(CONFIRMED)
                                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                                .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                                                .endDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                                .build()
                                ))
                                .inflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .outflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                .lastModification(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
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
                                                CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName(),
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
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
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
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);

        // verify lastMessageChecksum is set (contains MD5 hash of last event)
        assertThat(result.getLastMessageChecksum()).isNotNull();

        assertThat(result)
                .usingRecursiveComparison()
                .ignoringFields("lastMessageChecksum")
                .isEqualTo(
                        CashFlowDto.CashFlowSummaryJson.builder()
                                .cashFlowId(cashFlowId)
                                .userId("userId")
                                .name("cash-flow name")
                                .description("cash-flow description")
                                .bankAccount(new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(0, "USD")))
                                .status(CashFlow.CashFlowStatus.OPEN)
                                .cashChanges(Map.of(
                                        cashChangeId,
                                        CashFlowDto.CashChangeSummaryJson.builder()
                                                .cashChangeId(cashChangeId)
                                                .name("cash-change name edited")
                                                .description("cash-change description edited")
                                                .money(Money.of(200, "USD"))
                                                .type(INFLOW)
                                                .categoryName("Uncategorized")
                                                .status(PENDING)
                                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                                .dueDate(ZonedDateTime.parse("2024-01-11T00:00:00Z"))
                                                .endDate(null)
                                                .build()

                                ))
                                .inflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .outflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                .lastModification(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
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
                                                CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName(),
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
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
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
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);

        // verify lastMessageChecksum is set (contains MD5 hash of last event)
        assertThat(result.getLastMessageChecksum()).isNotNull();

        assertThat(result)
                .usingRecursiveComparison()
                .ignoringFields("lastMessageChecksum")
                .isEqualTo(
                        CashFlowDto.CashFlowSummaryJson.builder()
                                .cashFlowId(cashFlowId)
                                .userId("userId")
                                .name("cash-flow name")
                                .description("cash-flow description")
                                .bankAccount(new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(0, "USD")))
                                .status(CashFlow.CashFlowStatus.OPEN)
                                .cashChanges(Map.of(
                                        cashChangeId,
                                        CashFlowDto.CashChangeSummaryJson.builder()
                                                .cashChangeId(cashChangeId)
                                                .name("cash-change name")
                                                .description("cash-change description")
                                                .money(Money.of(100, "USD"))
                                                .type(INFLOW)
                                                .categoryName("Uncategorized")
                                                .status(REJECTED)
                                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                                .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                                                .endDate(null)
                                                .build()

                                ))
                                .inflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .outflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                .lastModification(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
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
                                                CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName(),
                                                CashFlowEvent.CashChangeRejectedEvent.class.getSimpleName()
                                        ))).orElse(false));

    }

    @Test
    void shouldAppendCashChangeToNewCategory() {
        // when
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("test category")
                        .type(INFLOW)
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("test category")
                        .name("cash-change name")
                        .description("cash-change description")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );

        // then
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);

        // verify lastMessageChecksum is set (contains MD5 hash of last event)
        assertThat(result.getLastMessageChecksum()).isNotNull();

        assertThat(result)
                .usingRecursiveComparison()
                .ignoringFields("lastMessageChecksum")
                .isEqualTo(
                        CashFlowDto.CashFlowSummaryJson.builder()
                                .cashFlowId(cashFlowId)
                                .userId("userId")
                                .name("cash-flow name")
                                .description("cash-flow description")
                                .bankAccount(new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(0, "USD")))
                                .status(CashFlow.CashFlowStatus.OPEN)
                                .cashChanges(Map.of(
                                        cashChangeId,
                                        CashFlowDto.CashChangeSummaryJson.builder()
                                                .cashChangeId(cashChangeId)
                                                .name("cash-change name")
                                                .description("cash-change description")
                                                .money(Money.of(100, "USD"))
                                                .type(INFLOW)
                                                .categoryName("test category")
                                                .status(PENDING)
                                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                                .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                                                .endDate(null)
                                                .build()

                                ))
                                .inflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        ),
                                        new Category(
                                                new CategoryName("test category"),
                                                new LinkedList<>(),
                                                true
                                        )
                                ))
                                .outflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                .lastModification(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
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
                                                CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName()
                                        ))).orElse(false));
    }

    @Test
    void shouldAppendCashChangeToSubCategory() {
        // when
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Overhead costs")
                        .parentCategoryName(null)
                        .type(OUTFLOW)
                        .build()
        );

        // adding subcategory
        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Bank fees")
                        .parentCategoryName("Overhead costs")
                        .type(OUTFLOW)
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Bank fees")
                        .name("Morgan Stanley fee")
                        .description("Bank fee")
                        .money(Money.of(67, "USD"))
                        .type(OUTFLOW)
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
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);

        // verify lastMessageChecksum is set (contains MD5 hash of last event)
        assertThat(result.getLastMessageChecksum()).isNotNull();

        assertThat(result)
                .usingRecursiveComparison()
                .ignoringFields("lastMessageChecksum")
                .isEqualTo(
                        CashFlowDto.CashFlowSummaryJson.builder()
                                .cashFlowId(cashFlowId)
                                .userId("userId")
                                .name("cash-flow name")
                                .description("cash-flow description")
                                .bankAccount(new BankAccount(
                                        new BankName("bank"),
                                        new BankAccountNumber("account number", Currency.of("USD")),
                                        Money.of(-67, "USD")))
                                .status(CashFlow.CashFlowStatus.OPEN)
                                .cashChanges(Map.of(
                                        cashChangeId,
                                        CashFlowDto.CashChangeSummaryJson.builder()
                                                .cashChangeId(cashChangeId)
                                                .name("Morgan Stanley fee")
                                                .description("Bank fee")
                                                .money(Money.of(67, "USD"))
                                                .type(OUTFLOW)
                                                .categoryName("Bank fees")
                                                .status(CONFIRMED)
                                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                                .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                                                .endDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                                .build()

                                ))
                                .inflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        )
                                ))
                                .outflowCategories(List.of(
                                        new Category(
                                                new CategoryName("Uncategorized"),
                                                new LinkedList<>(),
                                                false
                                        ),
                                        new Category(
                                                new CategoryName("Overhead costs"),
                                                List.of(
                                                        new Category(
                                                                new CategoryName("Bank fees"),
                                                                new LinkedList<>(),
                                                                true
                                                        )
                                                ),
                                                true
                                        )
                                ))
                                .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                                .lastModification(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
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
                                                CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName()
                                        ))).orElse(false));
    }

    @Test
    void shouldGetCashFlowsDetailsByUserId() {
        // given
        String userId = "test-user-123";

        // Create first cash flow for user
        String cashFlowId1 = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId(userId)
                        .name("Personal Budget")
                        .description("My personal budget")
                        .bankAccount(new BankAccount(
                                new BankName("Chase Bank"),
                                new BankAccountNumber("US123456789", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .build()
        );

        // Create second cash flow for same user
        String cashFlowId2 = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId(userId)
                        .name("Business Budget")
                        .description("My business budget")
                        .bankAccount(new BankAccount(
                                new BankName("Bank of America"),
                                new BankAccountNumber("US987654321", Currency.of("USD")),
                                Money.of(10000, "USD")))
                        .build()
        );

        // Create cash flow for different user (should not be returned)
        String otherUserId = "other-user-456";
        String cashFlowId3 = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId(otherUserId)
                        .name("Other User Budget")
                        .description("Other user budget")
                        .bankAccount(new BankAccount(
                                new BankName("Wells Fargo"),
                                new BankAccountNumber("US111222333", Currency.of("USD")),
                                Money.of(3000, "USD")))
                        .build()
        );

        // Add categories to first cash flow
        cashFlowRestController.createCategory(
                cashFlowId1,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Salary")
                        .type(INFLOW)
                        .build()
        );

        // Add cash changes to first cash flow
        String cashChangeId1 = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId1)
                        .category("Salary")
                        .name("Monthly Salary")
                        .description("January salary")
                        .money(Money.of(5000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );

        cashFlowRestController.confirm(
                CashFlowDto.ConfirmCashChangeJson.builder()
                        .cashFlowId(cashFlowId1)
                        .cashChangeId(cashChangeId1)
                        .build()
        );

        // Add categories to second cash flow
        cashFlowRestController.createCategory(
                cashFlowId2,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Revenue")
                        .type(INFLOW)
                        .build()
        );

        cashFlowRestController.createCategory(
                cashFlowId2,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Expenses")
                        .type(OUTFLOW)
                        .build()
        );

        // Add cash changes to second cash flow
        String cashChangeId2 = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId2)
                        .category("Revenue")
                        .name("Client Payment")
                        .description("Payment from client ABC")
                        .money(Money.of(2500, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-15T00:00:00Z"))
                        .build()
        );

        String cashChangeId3 = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId2)
                        .category("Expenses")
                        .name("Office Rent")
                        .description("Monthly office rent")
                        .money(Money.of(800, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-05T00:00:00Z"))
                        .build()
        );

        cashFlowRestController.confirm(
                CashFlowDto.ConfirmCashChangeJson.builder()
                        .cashFlowId(cashFlowId2)
                        .cashChangeId(cashChangeId3)
                        .build()
        );

        // when
        List<CashFlowDto.CashFlowDetailJson> cashFlowDetails = cashFlowRestController.getDetailsOfCashFlowViaUser(userId);

        // then
        assertThat(cashFlowDetails).hasSize(2);

        // Verify first cash flow details
        assertThat(cashFlowDetails)
                .anySatisfy(detail -> {
                    assertThat(detail.getCashFlowId()).isEqualTo(cashFlowId1);
                    assertThat(detail.getUserId()).isEqualTo(userId);
                    assertThat(detail.getName()).isEqualTo("Personal Budget");
                    assertThat(detail.getDescription()).isEqualTo("My personal budget");
                    assertThat(detail.getBankAccount().balance()).isEqualTo(Money.of(10000, "USD"));
                    assertThat(detail.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
                    assertThat(detail.getInflowCategories()).hasSize(2); // Uncategorized + Salary
                });

        // Verify second cash flow details
        assertThat(cashFlowDetails)
                .anySatisfy(detail -> {
                    assertThat(detail.getCashFlowId()).isEqualTo(cashFlowId2);
                    assertThat(detail.getUserId()).isEqualTo(userId);
                    assertThat(detail.getName()).isEqualTo("Business Budget");
                    assertThat(detail.getDescription()).isEqualTo("My business budget");
                    assertThat(detail.getBankAccount().balance()).isEqualTo(Money.of(9200, "USD"));
                    assertThat(detail.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
                    assertThat(detail.getInflowCategories()).hasSize(2); // Uncategorized + Revenue
                    assertThat(detail.getOutflowCategories()).hasSize(2); // Uncategorized + Expenses
                });

        // Verify other user's cash flow is not returned
        assertThat(cashFlowDetails)
                .noneMatch(detail -> detail.getCashFlowId().equals(cashFlowId3));

        // Verify empty result for non-existent user
        List<CashFlowDto.CashFlowDetailJson> emptyResult = cashFlowRestController.getDetailsOfCashFlowViaUser("non-existent-user");
        assertThat(emptyResult).isEmpty();
    }

    @Test
    void shouldSetBudgetingForCategory() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        // Create a category for budgeting
        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Groceries")
                        .type(OUTFLOW)
                        .build()
        );

        // when
        cashFlowRestController.setBudgeting(
                CashFlowDto.SetBudgetingJson.builder()
                        .cashFlowId(cashFlowId)
                        .categoryName("Groceries")
                        .categoryType(OUTFLOW)
                        .budget(Money.of(500, "USD"))
                        .build()
        );

        // then
        CashFlowDto.CashFlowSummaryJson cashFlow = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(cashFlow.getOutflowCategories())
                .anySatisfy(category -> {
                    assertThat(category.getCategoryName().name()).isEqualTo("Groceries");
                    assertThat(category.getBudgeting()).isNotNull();
                    assertThat(category.getBudgeting().budget()).isEqualTo(Money.of(500, "USD"));
                });

        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.BudgetingSetEvent.class.getSimpleName()))
                        .orElse(false));
    }

    @Test
    void shouldUpdateBudgetingForCategory() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        // Create a category and set budgeting
        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Entertainment")
                        .type(OUTFLOW)
                        .build()
        );

        cashFlowRestController.setBudgeting(
                CashFlowDto.SetBudgetingJson.builder()
                        .cashFlowId(cashFlowId)
                        .categoryName("Entertainment")
                        .categoryType(OUTFLOW)
                        .budget(Money.of(200, "USD"))
                        .build()
        );

        // when
        cashFlowRestController.updateBudgeting(
                CashFlowDto.UpdateBudgetingJson.builder()
                        .cashFlowId(cashFlowId)
                        .categoryName("Entertainment")
                        .categoryType(OUTFLOW)
                        .newBudget(Money.of(300, "USD"))
                        .build()
        );

        // then
        CashFlowDto.CashFlowSummaryJson cashFlow = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(cashFlow.getOutflowCategories())
                .anySatisfy(category -> {
                    assertThat(category.getCategoryName().name()).isEqualTo("Entertainment");
                    assertThat(category.getBudgeting()).isNotNull();
                    assertThat(category.getBudgeting().budget()).isEqualTo(Money.of(300, "USD"));
                });

        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .containsAll(List.of(
                                        CashFlowEvent.BudgetingSetEvent.class.getSimpleName(),
                                        CashFlowEvent.BudgetingUpdatedEvent.class.getSimpleName()
                                )))
                        .orElse(false));
    }

    @Test
    void shouldRemoveBudgetingFromCategory() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        // Create a category and set budgeting
        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Dining")
                        .type(OUTFLOW)
                        .build()
        );

        cashFlowRestController.setBudgeting(
                CashFlowDto.SetBudgetingJson.builder()
                        .cashFlowId(cashFlowId)
                        .categoryName("Dining")
                        .categoryType(OUTFLOW)
                        .budget(Money.of(150, "USD"))
                        .build()
        );

        // Verify budgeting was set
        CashFlowDto.CashFlowSummaryJson cashFlowBefore = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(cashFlowBefore.getOutflowCategories())
                .anySatisfy(category -> {
                    assertThat(category.getCategoryName().name()).isEqualTo("Dining");
                    assertThat(category.getBudgeting()).isNotNull();
                });

        // when
        cashFlowRestController.removeBudgeting(
                CashFlowDto.RemoveBudgetingJson.builder()
                        .cashFlowId(cashFlowId)
                        .categoryName("Dining")
                        .categoryType(OUTFLOW)
                        .build()
        );

        // then
        CashFlowDto.CashFlowSummaryJson cashFlowAfter = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(cashFlowAfter.getOutflowCategories())
                .anySatisfy(category -> {
                    assertThat(category.getCategoryName().name()).isEqualTo("Dining");
                    assertThat(category.getBudgeting()).isNull();
                });

        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .containsAll(List.of(
                                        CashFlowEvent.BudgetingSetEvent.class.getSimpleName(),
                                        CashFlowEvent.BudgetingRemovedEvent.class.getSimpleName()
                                )))
                        .orElse(false));
    }

    @Test
    void shouldSetBudgetingForInflowCategory() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        // Create an inflow category for budgeting
        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Salary")
                        .type(INFLOW)
                        .build()
        );

        // when
        cashFlowRestController.setBudgeting(
                CashFlowDto.SetBudgetingJson.builder()
                        .cashFlowId(cashFlowId)
                        .categoryName("Salary")
                        .categoryType(INFLOW)
                        .budget(Money.of(5000, "USD"))
                        .build()
        );

        // then
        CashFlowDto.CashFlowSummaryJson cashFlow = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(cashFlow.getInflowCategories())
                .anySatisfy(category -> {
                    assertThat(category.getCategoryName().name()).isEqualTo("Salary");
                    assertThat(category.getBudgeting()).isNotNull();
                    assertThat(category.getBudgeting().budget()).isEqualTo(Money.of(5000, "USD"));
                });

        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.BudgetingSetEvent.class.getSimpleName()))
                        .orElse(false));
    }

    @Test
    void shouldRejectPaidCashChangeWhenPaidDateInFuture() {
        // given - FixedClockConfig sets clock to 2022-01-01T00:00:00Z
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("test cash flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // when - trying to add paid cash change with paidDate in future (Feb 2022)
        // then - should throw PaidDateInFutureException
        assertThatThrownBy(() -> cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Future payment")
                        .description("This should fail")
                        .money(Money.of(500, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-02-15T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-02-15T00:00:00Z"))  // Future!
                        .build()
        )).isInstanceOf(PaidDateInFutureException.class);
    }

    @Test
    void shouldRejectPaidCashChangeWhenPaidDateNotInActivePeriod() {
        // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("test cash flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // when - trying to add paid cash change with paidDate in December 2021 (not active)
        // then - should throw PaidDateNotInActivePeriodException
        assertThatThrownBy(() -> cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Past payment")
                        .description("This should fail - past month")
                        .money(Money.of(500, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-15T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-12-15T00:00:00Z"))  // December 2021 - not active!
                        .build()
        )).isInstanceOf(PaidDateNotInActivePeriodException.class);
    }

    @Test
    void shouldAcceptPaidCashChangeInActivePeriod() {
        // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("test cash flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // when - adding paid cash change with paidDate on 2022-01-01 (active period, not future)
        String cashChangeId = cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Valid payment")
                        .description("This should succeed")
                        .money(Money.of(500, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))  // Jan 2022 - active!
                        .build()
        );

        // then - should succeed
        assertThat(cashChangeId).isNotNull();

        CashFlowDto.CashFlowSummaryJson cashFlow = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(cashFlow.getBankAccount().balance()).isEqualTo(Money.of(1500, "USD"));

        CashFlowDto.CashChangeSummaryJson cashChange = cashFlow.getCashChanges().get(cashChangeId);
        assertThat(cashChange.getStatus()).isEqualTo(CONFIRMED);
    }

    @Test
    void shouldRejectPaidCashChangeOutflowWhenPaidDateInFuture() {
        // given - FixedClockConfig sets clock to 2022-01-01T00:00:00Z
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("test cash flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .build()
        );

        // when - trying to add paid OUTFLOW with paidDate in future (Feb 2022)
        // then - should throw PaidDateInFutureException
        assertThatThrownBy(() -> cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Future rent")
                        .description("This should fail")
                        .money(Money.of(1500, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2022-02-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-02-01T00:00:00Z"))  // Future!
                        .build()
        )).isInstanceOf(PaidDateInFutureException.class);
    }

    @Test
    void shouldRejectPaidCashChangeOutflowWhenPaidDateNotInActivePeriod() {
        // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("test cash flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .build()
        );

        // when - trying to add paid OUTFLOW with paidDate in December 2021 (not active)
        // then - should throw PaidDateNotInActivePeriodException
        assertThatThrownBy(() -> cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Past rent")
                        .description("This should fail - past month")
                        .money(Money.of(1500, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-12-01T00:00:00Z"))  // December 2021 - not active!
                        .build()
        )).isInstanceOf(PaidDateNotInActivePeriodException.class);
    }

    @Test
    void shouldAcceptPaidCashChangeOutflowInActivePeriod() {
        // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("test cash flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .build()
        );

        // when - adding paid OUTFLOW with paidDate on 2022-01-01 (active period, not future)
        String cashChangeId = cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Valid rent payment")
                        .description("This should succeed")
                        .money(Money.of(1500, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))  // Jan 2022 - active!
                        .build()
        );

        // then - should succeed and decrease balance
        assertThat(cashChangeId).isNotNull();

        CashFlowDto.CashFlowSummaryJson cashFlow = cashFlowRestController.getCashFlow(cashFlowId);
        // Balance should decrease: 5000 - 1500 = 3500
        assertThat(cashFlow.getBankAccount().balance()).isEqualTo(Money.of(3500, "USD"));

        CashFlowDto.CashChangeSummaryJson cashChange = cashFlow.getCashChanges().get(cashChangeId);
        assertThat(cashChange.getStatus()).isEqualTo(CONFIRMED);
        assertThat(cashChange.getType()).isEqualTo(OUTFLOW);
    }

    // ==================== SETUP MODE VALIDATION TESTS ====================

    @Test
    void shouldRejectAppendExpectedCashChangeWhenCashFlowInSetupMode() {
        // given - create CashFlow with history (starts in SETUP mode)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("test cash flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // when/then - trying to append expected cash change should fail
        assertThatThrownBy(() -> cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Test transaction")
                        .description("This should fail")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-15T00:00:00Z"))
                        .build()
        )).isInstanceOf(OperationNotAllowedInSetupModeException.class)
                .hasMessageContaining("appendExpectedCashChange")
                .hasMessageContaining("SETUP mode");
    }

    @Test
    void shouldRejectAppendPaidCashChangeWhenCashFlowInSetupMode() {
        // given - create CashFlow with history (starts in SETUP mode)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("test cash flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // when/then - trying to append paid cash change should fail
        assertThatThrownBy(() -> cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Test payment")
                        .description("This should fail")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .build()
        )).isInstanceOf(OperationNotAllowedInSetupModeException.class)
                .hasMessageContaining("appendPaidCashChange")
                .hasMessageContaining("SETUP mode");
    }

    @Test
    void shouldRejectEditCashChangeWhenCashFlowInSetupMode() {
        // given - create CashFlow with history (starts in SETUP mode)
        // Note: In SETUP mode we can't add cash changes via normal flow,
        // so we test with a fake cashChangeId
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("test cash flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // when/then - trying to edit cash change should fail (even with non-existent cashChangeId)
        assertThatThrownBy(() -> cashFlowRestController.edit(
                CashFlowDto.EditCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .cashChangeId("fake-cash-change-id")
                        .name("Updated name")
                        .description("Updated description")
                        .money(Money.of(200, "USD"))
                        .dueDate(ZonedDateTime.parse("2022-01-20T00:00:00Z"))
                        .build()
        )).isInstanceOf(OperationNotAllowedInSetupModeException.class)
                .hasMessageContaining("editCashChange")
                .hasMessageContaining("SETUP mode");
    }

    @Test
    void shouldRejectConfirmCashChangeWhenCashFlowInSetupMode() {
        // given - create CashFlow with history (starts in SETUP mode)
        // Note: In SETUP mode we can't add cash changes via normal flow,
        // so we test with a fake cashChangeId
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("test cash flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // when/then - trying to confirm cash change should fail (even with non-existent cashChangeId)
        assertThatThrownBy(() -> cashFlowRestController.confirm(
                CashFlowDto.ConfirmCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .cashChangeId("fake-cash-change-id")
                        .build()
        )).isInstanceOf(OperationNotAllowedInSetupModeException.class)
                .hasMessageContaining("confirmCashChange")
                .hasMessageContaining("SETUP mode");
    }

    // ==================== CREATE CASH FLOW WITH HISTORY TESTS ====================

    @Test
    void shouldCreateCashFlowWithHistoryInSetupStatus() {
        // given - FixedClockConfig sets clock to 2022-01-01, so startPeriod 2021-06 is in the past
        // when
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("Cash flow with historical data support")
                        .bankAccount(new BankAccount(
                                new BankName("Chase Bank"),
                                new BankAccountNumber("US12345", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-06")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // then - CashFlow should be in SETUP status
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);

        assertThat(result.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(result.getUserId()).isEqualTo("userId");
        assertThat(result.getName()).isEqualTo("Historical Cash Flow");
        assertThat(result.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);
        assertThat(result.getBankAccount().balance()).isEqualTo(Money.of(5000, "USD"));

        // Wait for Kafka event to be processed
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(cashFlowForecastEntity -> cashFlowForecastEntity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.CashFlowWithHistoryCreatedEvent.class.getSimpleName()))
                        .orElse(false));
    }

    @Test
    void shouldCreateMonthlyForecastsWithCorrectStatusesForHistoricalCashFlow() {
        // given - FixedClockConfig sets clock to 2022-01-01
        // startPeriod: 2021-10 (historical)
        // activePeriod: 2022-01 (current)
        // So we expect:
        // - 2021-10, 2021-11, 2021-12: IMPORT_PENDING (historical months)
        // - 2022-01: ACTIVE (current month)
        // - 2022-02 to 2022-12: FORECASTED (future months)

        // when
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(2000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(500, "USD"))
                        .build()
        );

        // Wait for the forecast statement to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // then - verify monthly forecast statuses
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = statement.getForecasts();

        // Verify historical months are IMPORT_PENDING
        assertThat(forecasts.get(YearMonth.of(2021, 10)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.IMPORT_PENDING);
        assertThat(forecasts.get(YearMonth.of(2021, 11)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.IMPORT_PENDING);
        assertThat(forecasts.get(YearMonth.of(2021, 12)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.IMPORT_PENDING);

        // Verify active month is ACTIVE
        assertThat(forecasts.get(YearMonth.of(2022, 1)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.ACTIVE);

        // Verify future months are FORECASTED
        assertThat(forecasts.get(YearMonth.of(2022, 2)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.FORECASTED);
        assertThat(forecasts.get(YearMonth.of(2022, 12)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.FORECASTED);

        // Verify total count of monthly forecasts: 3 historical + 1 active + 11 future = 15
        assertThat(forecasts).hasSize(15);
    }

    @Test
    void shouldRejectCashFlowWithHistoryWhenStartPeriodIsInFuture() {
        // given - FixedClockConfig sets clock to 2022-01-01
        // Trying to create with startPeriod 2023-01 (future) should fail

        // when/then
        assertThatThrownBy(() -> cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Future Start Period")
                        .description("This should fail")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .startPeriod("2023-01")  // Future!
                        .initialBalance(Money.of(500, "USD"))
                        .build()
        )).isInstanceOf(StartPeriodInFutureException.class);
    }

    @Test
    void shouldCreateCashFlowWithHistoryStartingFromCurrentMonth() {
        // given - FixedClockConfig sets clock to 2022-01-01
        // startPeriod same as activePeriod (2022-01) - edge case, no historical months
        // when
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Current Month Start")
                        .description("No historical data")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(3000, "USD")))
                        .startPeriod("2022-01")  // Same as current active period
                        .initialBalance(Money.of(3000, "USD"))
                        .build()
        );

        // Wait for the forecast statement to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // then
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = statement.getForecasts();

        // No historical months - only active and future
        // 1 active + 11 future = 12 months
        assertThat(forecasts).hasSize(12);

        // First month should be ACTIVE (current month)
        assertThat(forecasts.get(YearMonth.of(2022, 1)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.ACTIVE);

        // CashFlow should be in SETUP status
        CashFlowDto.CashFlowSummaryJson result = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(result.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);
    }

    // ==================== IMPORT HISTORICAL CASH CHANGE TESTS ====================

    @Test
    void shouldImportHistoricalCashChangeToSetupPendingMonth() {
        // given - create CashFlow with history starting from 2021-10 (clock is 2022-01-01)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For import testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // when - import historical transaction to November 2021 (IMPORT_PENDING month)
        String cashChangeId = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Historical Salary")
                        .description("November salary")
                        .money(Money.of(3000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-25T00:00:00Z"))
                        .build()
        );

        // then - verify cash change was created
        assertThat(cashChangeId).isNotNull();

        // Verify cash change exists in CashFlow aggregate
        CashFlowDto.CashFlowSummaryJson cashFlowSummary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(cashFlowSummary.getCashChanges()).containsKey(cashChangeId);
        assertThat(cashFlowSummary.getCashChanges().get(cashChangeId).getStatus()).isEqualTo(CONFIRMED);

        // Wait for Kafka event to be processed
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .anyMatch(p -> p.type().equals("HistoricalCashChangeImportedEvent")))
                        .orElse(false));
    }

    @Test
    void shouldRejectImportWhenCashFlowNotInSetupMode() {
        // given - create normal CashFlow (OPEN mode, not SETUP)
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Normal Cash Flow")
                        .description("In OPEN mode")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // when/then - trying to import historical data should fail
        assertThatThrownBy(() -> cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Test Transaction")
                        .description("This should fail")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-15T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-15T00:00:00Z"))
                        .build()
        )).isInstanceOf(ImportNotAllowedInNonSetupModeException.class);
    }

    @Test
    void shouldRejectImportWhenPaidDateIsInActiveOrFuturePeriod() {
        // given - create CashFlow with history (clock is 2022-01-01, activePeriod = 2022-01)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For import testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(2000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(500, "USD"))
                        .build()
        );

        // when/then - trying to import to active period (2022-01) should fail
        assertThatThrownBy(() -> cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("January Transaction")
                        .description("This should fail - not historical")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-15T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-15T00:00:00Z"))  // Active period!
                        .build()
        )).isInstanceOf(ImportDateOutsideSetupPeriodException.class);
    }

    @Test
    void shouldRejectImportWhenPaidDateIsBeforeStartPeriod() {
        // given - create CashFlow with history starting from 2021-10
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For import testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(2000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(500, "USD"))
                        .build()
        );

        // when/then - trying to import to month before startPeriod (2021-09) should fail
        assertThatThrownBy(() -> cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("September Transaction")
                        .description("This should fail - before startPeriod")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-09-15T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-09-15T00:00:00Z"))  // Before startPeriod!
                        .build()
        )).isInstanceOf(ImportDateBeforeStartPeriodException.class);
    }

    @Test
    void shouldImportMultipleHistoricalTransactionsToDifferentMonths() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("Multiple imports")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(10000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // when - import transactions to different historical months
        String oct2021Id = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("October Salary")
                        .description("Oct 2021")
                        .money(Money.of(3000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        String nov2021Id = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("November Rent")
                        .description("Nov 2021")
                        .money(Money.of(1500, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-01T00:00:00Z"))
                        .build()
        );

        String dec2021Id = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("December Bonus")
                        .description("Dec 2021")
                        .money(Money.of(5000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-20T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-12-20T00:00:00Z"))
                        .build()
        );

        // then - verify all transactions exist
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summary.getCashChanges()).hasSize(3);
        assertThat(summary.getCashChanges()).containsKeys(oct2021Id, nov2021Id, dec2021Id);

        // All should be CONFIRMED (historical transactions)
        assertThat(summary.getCashChanges().get(oct2021Id).getStatus()).isEqualTo(CONFIRMED);
        assertThat(summary.getCashChanges().get(nov2021Id).getStatus()).isEqualTo(CONFIRMED);
        assertThat(summary.getCashChanges().get(dec2021Id).getStatus()).isEqualTo(CONFIRMED);
    }

    @Test
    void shouldUpdateForecastWhenImportingHistoricalInflow() {
        // given - create CashFlow with history starting from 2021-10 (clock is 2022-01-01)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For forecast testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // when - import historical INFLOW transaction to November 2021
        String cashChangeId = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Historical Salary")
                        .description("November salary")
                        .money(Money.of(3000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-25T00:00:00Z"))
                        .build()
        );

        // Wait for Kafka event to be processed and forecast updated
        Awaitility.await().until(() -> {
            CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
            CashFlowMonthlyForecast nov2021 = statement.getForecasts().get(YearMonth.of(2021, 11));
            return nov2021.getCashFlowStats().getInflowStats().actual().equals(Money.of(3000, "USD"));
        });

        // then - verify forecast was updated correctly
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
        CashFlowMonthlyForecast nov2021Forecast = statement.getForecasts().get(YearMonth.of(2021, 11));

        // Verify inflow stats - actual should be updated (not expected)
        assertThat(nov2021Forecast.getCashFlowStats().getInflowStats().actual())
                .isEqualTo(Money.of(3000, "USD"));
        assertThat(nov2021Forecast.getCashFlowStats().getInflowStats().expected())
                .isEqualTo(Money.of(0, "USD"));

        // Verify transaction is in PAID group (not PENDING/EXPECTED)
        CashCategory inflowCategory = nov2021Forecast.findCategoryInflowsByCategoryName(new CategoryName("Uncategorized")).orElseThrow();
        assertThat(inflowCategory.getGroupedTransactions().get(PaymentStatus.PAID))
                .hasSize(1)
                .anySatisfy(tx -> {
                    assertThat(tx.getCashChangeId().id()).isEqualTo(cashChangeId);
                    assertThat(tx.getMoney()).isEqualTo(Money.of(3000, "USD"));
                });

        // Verify totalPaidValue is updated
        assertThat(inflowCategory.getTotalPaidValue()).isEqualTo(Money.of(3000, "USD"));
    }

    @Test
    void shouldUpdateForecastWhenImportingHistoricalOutflow() {
        // given - create CashFlow with history starting from 2021-10 (clock is 2022-01-01)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For outflow forecast testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // when - import historical OUTFLOW transaction to December 2021
        String cashChangeId = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Historical Rent")
                        .description("December rent payment")
                        .money(Money.of(1500, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-12-01T00:00:00Z"))
                        .build()
        );

        // Wait for Kafka event to be processed and forecast updated
        Awaitility.await().until(() -> {
            CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
            CashFlowMonthlyForecast dec2021 = statement.getForecasts().get(YearMonth.of(2021, 12));
            return dec2021.getCashFlowStats().getOutflowStats().actual().equals(Money.of(1500, "USD"));
        });

        // then - verify forecast was updated correctly
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
        CashFlowMonthlyForecast dec2021Forecast = statement.getForecasts().get(YearMonth.of(2021, 12));

        // Verify outflow stats - actual should be updated (not expected)
        assertThat(dec2021Forecast.getCashFlowStats().getOutflowStats().actual())
                .isEqualTo(Money.of(1500, "USD"));
        assertThat(dec2021Forecast.getCashFlowStats().getOutflowStats().expected())
                .isEqualTo(Money.of(0, "USD"));

        // Verify transaction is in PAID group (not PENDING/EXPECTED)
        CashCategory outflowCategory = dec2021Forecast.findCategoryOutflowsByCategoryName(new CategoryName("Uncategorized")).orElseThrow();
        assertThat(outflowCategory.getGroupedTransactions().get(PaymentStatus.PAID))
                .hasSize(1)
                .anySatisfy(tx -> {
                    assertThat(tx.getCashChangeId().id()).isEqualTo(cashChangeId);
                    assertThat(tx.getMoney()).isEqualTo(Money.of(1500, "USD"));
                });

        // Verify totalPaidValue is updated
        assertThat(outflowCategory.getTotalPaidValue()).isEqualTo(Money.of(1500, "USD"));
    }

    @Test
    void shouldUpdateForecastWithMultipleHistoricalTransactionsInSameMonth() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("Multiple transactions same month")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(10000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(2000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // when - import multiple transactions to November 2021
        // INFLOW 1: Salary
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Salary")
                        .description("Nov salary")
                        .money(Money.of(5000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-25T00:00:00Z"))
                        .build()
        );

        // INFLOW 2: Bonus
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Bonus")
                        .description("Nov bonus")
                        .money(Money.of(1000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-28T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-28T00:00:00Z"))
                        .build()
        );

        // OUTFLOW 1: Rent
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Rent")
                        .description("Nov rent")
                        .money(Money.of(2000, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-01T00:00:00Z"))
                        .build()
        );

        // OUTFLOW 2: Utilities
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Utilities")
                        .description("Nov utilities")
                        .money(Money.of(300, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-15T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-15T00:00:00Z"))
                        .build()
        );

        // Wait for all Kafka events to be processed
        Awaitility.await().until(() -> {
            CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
            CashFlowMonthlyForecast nov2021 = statement.getForecasts().get(YearMonth.of(2021, 11));
            // Total inflows: 5000 + 1000 = 6000
            // Total outflows: 2000 + 300 = 2300
            return nov2021.getCashFlowStats().getInflowStats().actual().equals(Money.of(6000, "USD"))
                    && nov2021.getCashFlowStats().getOutflowStats().actual().equals(Money.of(2300, "USD"));
        });

        // then - verify forecast totals
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
        CashFlowMonthlyForecast nov2021Forecast = statement.getForecasts().get(YearMonth.of(2021, 11));

        // Verify aggregated inflow stats
        assertThat(nov2021Forecast.getCashFlowStats().getInflowStats().actual())
                .isEqualTo(Money.of(6000, "USD"));

        // Verify aggregated outflow stats
        assertThat(nov2021Forecast.getCashFlowStats().getOutflowStats().actual())
                .isEqualTo(Money.of(2300, "USD"));

        // Verify transaction counts in PAID groups
        CashCategory inflowCategory = nov2021Forecast.findCategoryInflowsByCategoryName(new CategoryName("Uncategorized")).orElseThrow();
        assertThat(inflowCategory.getGroupedTransactions().get(PaymentStatus.PAID)).hasSize(2);
        assertThat(inflowCategory.getTotalPaidValue()).isEqualTo(Money.of(6000, "USD"));

        CashCategory outflowCategory = nov2021Forecast.findCategoryOutflowsByCategoryName(new CategoryName("Uncategorized")).orElseThrow();
        assertThat(outflowCategory.getGroupedTransactions().get(PaymentStatus.PAID)).hasSize(2);
        assertThat(outflowCategory.getTotalPaidValue()).isEqualTo(Money.of(2300, "USD"));
    }

    // ===================================================================================
    // Import cutoff validation tests (paidDate <= now)
    // Clock is fixed at 2022-01-01T00:00:00Z
    // ===================================================================================

    @Test
    void shouldRejectImportWhenPaidDateIsInTheFuture() {
        // given - create CashFlow with history (clock is 2022-01-01T00:00:00Z)
        // Use startPeriod that includes December 2021 so we can test future dates within that month
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For cutoff testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // when/then - trying to import with paidDate in the future should fail
        // Clock is 2022-01-01T00:00:00Z, trying to import in Dec 2021 (valid month) but with future timestamp
        // Note: 2022-01-01T00:00:01Z is in activePeriod (Jan 2022), so it will be rejected by validation 2 first.
        // To test validation 4 specifically, we would need paidDate in historical month but > now,
        // which is not possible since clock is at 2022-01-01T00:00:00Z and activePeriod is 2022-01.
        // This test verifies that dates in activePeriod are rejected (by validation 2).
        assertThatThrownBy(() -> cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Future Transaction")
                        .description("This should fail - in activePeriod")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-15T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-01T00:00:01Z"))  // In activePeriod (Jan 2022)
                        .build()
        )).isInstanceOf(ImportDateOutsideSetupPeriodException.class);
    }

    @Test
    void shouldRejectImportWhenPaidDateIsOneHourInTheFuture() {
        // given - create CashFlow with history (clock is 2022-01-01T00:00:00Z)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For cutoff testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // when/then - trying to import with paidDate 1 hour in the future should fail
        // This is in activePeriod (Jan 2022), so validation 2 rejects it first
        assertThatThrownBy(() -> cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Future Transaction")
                        .description("This should fail - in activePeriod")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-15T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-01T01:00:00Z"))  // In activePeriod (Jan 2022)
                        .build()
        )).isInstanceOf(ImportDateOutsideSetupPeriodException.class);
    }

    @Test
    void shouldAcceptImportWhenPaidDateIsExactlyNow() {
        // given - create CashFlow with history (clock is 2022-01-01T00:00:00Z)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For cutoff testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // when - import with paidDate exactly at clock time (boundary case)
        // Note: This will fail validation 2 because 2022-01 is activePeriod, not historical
        // So we need to use a date in December 2021 that is "exactly now" in concept
        // Since clock is 2022-01-01T00:00:00Z, we test with last moment of 2021
        String cashChangeId = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Last Second of 2021")
                        .description("Boundary test - last valid moment")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-31T23:59:59Z"))
                        .paidDate(ZonedDateTime.parse("2021-12-31T23:59:59Z"))  // 1 second before clock
                        .build()
        );

        // then - should succeed
        assertThat(cashChangeId).isNotNull();
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summary.getCashChanges()).containsKey(cashChangeId);
    }

    @Test
    void shouldAcceptImportWhenPaidDateIsOneSecondBeforeNow() {
        // given - create CashFlow with history (clock is 2022-01-01T00:00:00Z)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For cutoff testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // when - import with paidDate 1 second before clock
        String cashChangeId = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("One Second Before")
                        .description("Boundary test - 1 second before now")
                        .money(Money.of(200, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-31T23:59:59Z"))
                        .paidDate(ZonedDateTime.parse("2021-12-31T23:59:59Z"))
                        .build()
        );

        // then - should succeed
        assertThat(cashChangeId).isNotNull();
    }

    @Test
    void shouldAcceptImportWhenPaidDateIsEarlierInTheSameDay() {
        // given - create CashFlow with history (clock is 2022-01-01T00:00:00Z)
        // Simulating: user starts Vidulum at midnight, imports transaction from morning of Dec 31
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For same-day testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // when - import transaction from morning of December 31, 2021
        String cashChangeId = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Morning Coffee")
                        .description("Transaction from earlier that day")
                        .money(Money.of(5, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-31T08:30:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-12-31T08:30:00Z"))
                        .build()
        );

        // then - should succeed
        assertThat(cashChangeId).isNotNull();
    }

    @Test
    void shouldRejectImportWhenPaidDateIsTomorrowMorning() {
        // given - create CashFlow with history (clock is 2022-01-01T00:00:00Z)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For future date testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // when/then - trying to import with paidDate tomorrow morning should fail
        assertThatThrownBy(() -> cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Tomorrow Transaction")
                        .description("This should fail - tomorrow")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-15T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-02T08:00:00Z"))  // tomorrow morning (in activePeriod 2022-01)
                        .build()
        )).isInstanceOf(ImportDateOutsideSetupPeriodException.class);  // validation 2 catches it first (paidDate in activePeriod)
    }

    @Test
    void shouldRejectImportWhenPaidDateIsNextMonth() {
        // given - create CashFlow with history (clock is 2022-01-01T00:00:00Z)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For future month testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // when/then - trying to import with paidDate next month should fail
        assertThatThrownBy(() -> cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Next Month Transaction")
                        .description("This should fail - next month")
                        .money(Money.of(100, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-15T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-02-15T10:00:00Z"))  // February 2022 (after activePeriod 2022-01)
                        .build()
        )).isInstanceOf(ImportDateOutsideSetupPeriodException.class);  // validation 2 catches it first (paidDate after activePeriod)
    }

    @Test
    void shouldAcceptMultipleImportsFromDifferentTimesOnSameHistoricalDay() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("Multiple same-day imports")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // when - import multiple transactions from December 15, 2021 at different times
        String morningId = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Morning Coffee")
                        .description("08:00")
                        .money(Money.of(5, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-15T08:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-12-15T08:00:00Z"))
                        .build()
        );

        String lunchId = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Lunch")
                        .description("12:30")
                        .money(Money.of(15, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-15T12:30:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-12-15T12:30:00Z"))
                        .build()
        );

        String eveningId = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Dinner")
                        .description("19:00")
                        .money(Money.of(30, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-12-15T19:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-12-15T19:00:00Z"))
                        .build()
        );

        // then - all should succeed
        assertThat(morningId).isNotNull();
        assertThat(lunchId).isNotNull();
        assertThat(eveningId).isNotNull();

        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summary.getCashChanges()).hasSize(3);
    }
}
