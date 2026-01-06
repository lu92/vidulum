package com.multi.vidulum.cashflow;

import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.app.CashFlowRestController;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowMonthlyForecast;
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
        // - 2021-10, 2021-11, 2021-12: SETUP_PENDING (historical months)
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

        // Verify historical months are SETUP_PENDING
        assertThat(forecasts.get(YearMonth.of(2021, 10)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.SETUP_PENDING);
        assertThat(forecasts.get(YearMonth.of(2021, 11)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.SETUP_PENDING);
        assertThat(forecasts.get(YearMonth.of(2021, 12)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.SETUP_PENDING);

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
}
