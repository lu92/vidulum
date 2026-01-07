package com.multi.vidulum.cashflow;

import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.app.CashFlowRestController;
import com.multi.vidulum.cashflow.app.commands.archive.CannotArchiveSystemCategoryException;
import com.multi.vidulum.cashflow.app.commands.archive.CategoryNotFoundException;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow_forecast_processor.app.CashCategory;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowMonthlyForecast;
import com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus;
import com.multi.vidulum.cashflow_forecast_processor.app.TransactionDetails;
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
                                .inflowCategories(List.of(Category.createUncategorized()))
                                .outflowCategories(List.of(Category.createUncategorized()))
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
                                .inflowCategories(List.of(Category.createUncategorized()))
                                .outflowCategories(List.of(Category.createUncategorized()))
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
                                .inflowCategories(List.of(Category.createUncategorized()))
                                .outflowCategories(List.of(Category.createUncategorized()))
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
                                .inflowCategories(List.of(Category.createUncategorized()))
                                .outflowCategories(List.of(Category.createUncategorized()))
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
                                .inflowCategories(List.of(Category.createUncategorized()))
                                .outflowCategories(List.of(Category.createUncategorized()))
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
                                .inflowCategories(List.of(Category.createUncategorized()))
                                .outflowCategories(List.of(Category.createUncategorized()))
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
                                        Category.createUncategorized(),
                                        Category.builder()
                                                .categoryName(new CategoryName("test category"))
                                                .subCategories(new LinkedList<>())
                                                .isModifiable(true)
                                                .origin(CategoryOrigin.USER_CREATED)
                                                .build()
                                ))
                                .outflowCategories(List.of(Category.createUncategorized()))
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
                                .inflowCategories(List.of(Category.createUncategorized()))
                                .outflowCategories(List.of(
                                        Category.createUncategorized(),
                                        Category.builder()
                                                .categoryName(new CategoryName("Overhead costs"))
                                                .subCategories(List.of(
                                                        Category.builder()
                                                                .categoryName(new CategoryName("Bank fees"))
                                                                .subCategories(new LinkedList<>())
                                                                .isModifiable(true)
                                                                .origin(CategoryOrigin.USER_CREATED)
                                                                .build()
                                                ))
                                                .isModifiable(true)
                                                .origin(CategoryOrigin.USER_CREATED)
                                                .build()
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

    // ==================== ATTEST HISTORICAL IMPORT TESTS ====================

    @Test
    void shouldAttestHistoricalImportWhenBalancesMatch() {
        // given - create CashFlow with history starting from 2021-10 (clock is 2022-01-01)
        // initialBalance = 1000 USD
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For activation testing")
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

        // Import some historical transactions
        // INFLOW: +3000 USD (salary Oct)
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("October Salary")
                        .description("Oct salary")
                        .money(Money.of(3000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // OUTFLOW: -500 USD (rent Nov)
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("November Rent")
                        .description("Nov rent")
                        .money(Money.of(500, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-01T00:00:00Z"))
                        .build()
        );

        // Expected balance: 1000 (initial) + 3000 (inflow) - 500 (outflow) = 3500 USD

        // when - activate with matching balance
        CashFlowDto.AttestHistoricalImportResponseJson response = cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(3500, "USD"))
                        .forceAttestation(false)
                        .build()
        );

        // then - CashFlow should be activated (OPEN status)
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(response.getConfirmedBalance()).isEqualTo(Money.of(3500, "USD"));
        assertThat(response.getCalculatedBalance()).isEqualTo(Money.of(3500, "USD"));
        assertThat(response.getDifference()).isEqualTo(Money.of(0, "USD"));
        assertThat(response.isForced()).isFalse();

        // Verify CashFlow status via getCashFlow
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summary.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(summary.getBankAccount().balance()).isEqualTo(Money.of(3500, "USD"));

        // Wait for Kafka event to be processed
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .anyMatch(p -> p.type().equals("HistoricalImportAttestedEvent")))
                        .orElse(false));

        // Verify IMPORT_PENDING months changed to IMPORTED
        Awaitility.await().until(() -> {
            CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
            return statement.getForecasts().get(YearMonth.of(2021, 10)).getStatus() == CashFlowMonthlyForecast.Status.IMPORTED
                    && statement.getForecasts().get(YearMonth.of(2021, 11)).getStatus() == CashFlowMonthlyForecast.Status.IMPORTED
                    && statement.getForecasts().get(YearMonth.of(2021, 12)).getStatus() == CashFlowMonthlyForecast.Status.IMPORTED;
        });
    }

    @Test
    void shouldRejectAttestationWhenBalancesMismatchAndNotForced() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For balance mismatch testing")
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

        // Import transaction: +2000 USD
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Salary")
                        .description("Salary")
                        .money(Money.of(2000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // Expected balance: 1000 (initial) + 2000 (inflow) = 3000 USD
        // But user tries to confirm with 4000 USD (mismatch!)

        // when/then - should throw BalanceMismatchException
        assertThatThrownBy(() -> cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(4000, "USD"))  // Wrong balance!
                        .forceAttestation(false)
                        .build()
        )).isInstanceOf(BalanceMismatchException.class);

        // Verify CashFlow is still in SETUP status
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summary.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);
    }

    @Test
    void shouldForceAttestationWhenBalancesMismatch() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For force activation testing")
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

        // Import transaction: +2000 USD
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Salary")
                        .description("Salary")
                        .money(Money.of(2000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // Expected balance: 1000 (initial) + 2000 (inflow) = 3000 USD
        // But user confirms with 3500 USD (mismatch of 500 USD)
        // With forceActivation=true, this should succeed

        // when - force activation despite balance mismatch
        CashFlowDto.AttestHistoricalImportResponseJson response = cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(3500, "USD"))  // 500 USD mismatch
                        .forceAttestation(true)
                        .build()
        );

        // then - CashFlow should be activated with forced flag
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(response.getConfirmedBalance()).isEqualTo(Money.of(3500, "USD"));
        assertThat(response.getCalculatedBalance()).isEqualTo(Money.of(3000, "USD"));
        assertThat(response.getDifference()).isEqualTo(Money.of(500, "USD"));
        assertThat(response.isForced()).isTrue();

        // Verify CashFlow status and balance
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summary.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(summary.getBankAccount().balance()).isEqualTo(Money.of(3500, "USD"));  // Confirmed balance used
    }

    @Test
    void shouldRejectAttestationWhenCashFlowNotInSetupMode() {
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

        // when/then - trying to activate should fail (already OPEN)
        assertThatThrownBy(() -> cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(1000, "USD"))
                        .forceAttestation(false)
                        .build()
        )).isInstanceOf(AttestationNotAllowedInNonSetupModeException.class);
    }

    @Test
    void shouldAttestHistoricalImportWithNoImportedTransactions() {
        // given - create CashFlow with history but don't import any transactions
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Empty Historical Cash Flow")
                        .description("No imports")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(2000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(2000, "USD"))  // Same as bank balance
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // Expected balance: 2000 USD (initial, no transactions)

        // when - activate with matching balance
        CashFlowDto.AttestHistoricalImportResponseJson response = cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(2000, "USD"))
                        .forceAttestation(false)
                        .build()
        );

        // then
        assertThat(response.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(response.getCalculatedBalance()).isEqualTo(Money.of(2000, "USD"));
        assertThat(response.getDifference()).isEqualTo(Money.of(0, "USD"));
    }

    @Test
    void shouldChangeImportPendingToImportedAfterAttestation() {
        // given - create CashFlow with history (Oct 2021 - Dec 2021 are IMPORT_PENDING)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For status change testing")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // Verify initial statuses before activation
        CashFlowForecastStatement statementBefore = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
        assertThat(statementBefore.getForecasts().get(YearMonth.of(2021, 10)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.IMPORT_PENDING);
        assertThat(statementBefore.getForecasts().get(YearMonth.of(2021, 11)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.IMPORT_PENDING);
        assertThat(statementBefore.getForecasts().get(YearMonth.of(2021, 12)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.IMPORT_PENDING);
        assertThat(statementBefore.getForecasts().get(YearMonth.of(2022, 1)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.ACTIVE);

        // when - activate
        cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(1000, "USD"))
                        .forceAttestation(false)
                        .build()
        );

        // then - wait for status changes
        Awaitility.await().until(() -> {
            CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
            return statement.getForecasts().get(YearMonth.of(2021, 10)).getStatus() == CashFlowMonthlyForecast.Status.IMPORTED;
        });

        CashFlowForecastStatement statementAfter = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();

        // Verify historical months changed from IMPORT_PENDING to IMPORTED
        assertThat(statementAfter.getForecasts().get(YearMonth.of(2021, 10)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.IMPORTED);
        assertThat(statementAfter.getForecasts().get(YearMonth.of(2021, 11)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.IMPORTED);
        assertThat(statementAfter.getForecasts().get(YearMonth.of(2021, 12)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.IMPORTED);

        // Verify ACTIVE month remains ACTIVE
        assertThat(statementAfter.getForecasts().get(YearMonth.of(2022, 1)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.ACTIVE);

        // Verify FORECASTED months remain FORECASTED
        assertThat(statementAfter.getForecasts().get(YearMonth.of(2022, 2)).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.FORECASTED);
    }

    @Test
    void shouldAttestHistoricalImportWithNegativeBalanceDifference() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For negative difference testing")
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

        // Import transaction: +2000 USD
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Salary")
                        .description("Salary")
                        .money(Money.of(2000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // Expected balance: 1000 (initial) + 2000 (inflow) = 3000 USD
        // User confirms with 2500 USD (negative mismatch of -500 USD)

        // when - force activation with negative difference
        CashFlowDto.AttestHistoricalImportResponseJson response = cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(2500, "USD"))  // -500 USD mismatch
                        .forceAttestation(true)
                        .build()
        );

        // then
        assertThat(response.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(response.getConfirmedBalance()).isEqualTo(Money.of(2500, "USD"));
        assertThat(response.getCalculatedBalance()).isEqualTo(Money.of(3000, "USD"));
        assertThat(response.getDifference()).isEqualTo(Money.of(-500, "USD"));
        assertThat(response.isForced()).isTrue();
    }

    @Test
    void shouldCreateAdjustmentTransactionWhenBalanceDiffersAndCreateAdjustmentIsTrue() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For adjustment testing")
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

        // Import transaction: +2000 USD
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Salary")
                        .description("Salary")
                        .money(Money.of(2000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // Expected balance: 1000 (initial) + 2000 (inflow) = 3000 USD
        // User confirms with 3500 USD (positive mismatch of +500 USD)
        // With createAdjustment=true, should create an INFLOW adjustment of 500 USD

        // when - attest with createAdjustment=true
        CashFlowDto.AttestHistoricalImportResponseJson response = cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(3500, "USD"))
                        .forceAttestation(false)  // not needed when createAdjustment is true
                        .createAdjustment(true)
                        .build()
        );

        // then - adjustment should be created
        assertThat(response.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(response.getConfirmedBalance()).isEqualTo(Money.of(3500, "USD"));
        assertThat(response.getCalculatedBalance()).isEqualTo(Money.of(3000, "USD"));
        assertThat(response.getDifference()).isEqualTo(Money.of(500, "USD"));
        assertThat(response.isAdjustmentCreated()).isTrue();
        assertThat(response.getAdjustmentCashChangeId()).isNotNull();

        // Verify CashFlow has the adjustment transaction
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summary.getCashChanges()).hasSize(2);  // original + adjustment

        // Find the adjustment transaction
        CashFlowDto.CashChangeSummaryJson adjustment = summary.getCashChanges().get(response.getAdjustmentCashChangeId());
        assertThat(adjustment).isNotNull();
        assertThat(adjustment.getName()).isEqualTo("Balance Adjustment");
        assertThat(adjustment.getMoney()).isEqualTo(Money.of(500, "USD"));
        assertThat(adjustment.getType()).isEqualTo(INFLOW);
    }

    @Test
    void shouldCreateNegativeAdjustmentTransactionWhenConfirmedBalanceIsLower() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For negative adjustment testing")
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

        // Import transaction: +2000 USD
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Salary")
                        .description("Salary")
                        .money(Money.of(2000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // Expected balance: 1000 (initial) + 2000 (inflow) = 3000 USD
        // User confirms with 2500 USD (negative mismatch of -500 USD)
        // With createAdjustment=true, should create an OUTFLOW adjustment of 500 USD

        // when - attest with createAdjustment=true
        CashFlowDto.AttestHistoricalImportResponseJson response = cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(2500, "USD"))
                        .forceAttestation(false)
                        .createAdjustment(true)
                        .build()
        );

        // then - adjustment should be created as OUTFLOW
        assertThat(response.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(response.getDifference()).isEqualTo(Money.of(-500, "USD"));
        assertThat(response.isAdjustmentCreated()).isTrue();

        // Verify the adjustment is an OUTFLOW
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        CashFlowDto.CashChangeSummaryJson adjustment = summary.getCashChanges().get(response.getAdjustmentCashChangeId());
        assertThat(adjustment.getType()).isEqualTo(OUTFLOW);
        assertThat(adjustment.getMoney()).isEqualTo(Money.of(500, "USD"));
    }

    @Test
    void shouldNotCreateAdjustmentWhenBalancesMatch() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For no-adjustment testing")
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

        // Import transaction: +2000 USD
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Salary")
                        .description("Salary")
                        .money(Money.of(2000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // Expected balance: 1000 (initial) + 2000 (inflow) = 3000 USD

        // when - attest with matching balance and createAdjustment=true
        CashFlowDto.AttestHistoricalImportResponseJson response = cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(3000, "USD"))
                        .forceAttestation(false)
                        .createAdjustment(true)
                        .build()
        );

        // then - no adjustment needed
        assertThat(response.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(response.getDifference()).isEqualTo(Money.of(0, "USD"));
        assertThat(response.isAdjustmentCreated()).isFalse();
        assertThat(response.getAdjustmentCashChangeId()).isNull();

        // Verify only 1 transaction (no adjustment)
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summary.getCashChanges()).hasSize(1);
    }

    @Test
    void shouldSetImportCutoffDateTimeAfterAttestation() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For importCutoffDateTime testing")
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

        // Verify importCutoffDateTime is null before attestation
        CashFlowDto.CashFlowSummaryJson summaryBefore = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summaryBefore.getImportCutoffDateTime()).isNull();

        // when - attest
        cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(1000, "USD"))
                        .forceAttestation(false)
                        .build()
        );

        // then - importCutoffDateTime should be set
        CashFlowDto.CashFlowSummaryJson summaryAfter = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summaryAfter.getImportCutoffDateTime()).isNotNull();
        assertThat(summaryAfter.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
    }

    @Test
    void shouldAddAdjustmentToActiveMonthInForecast() {
        // given - create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For forecast adjustment testing")
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

        // Import transaction: +2000 USD
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Salary")
                        .description("Salary")
                        .money(Money.of(2000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // when - attest with positive difference and createAdjustment=true
        CashFlowDto.AttestHistoricalImportResponseJson response = cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(3500, "USD"))  // +500 difference
                        .forceAttestation(false)
                        .createAdjustment(true)
                        .build()
        );

        assertThat(response.isAdjustmentCreated()).isTrue();

        // then - wait for Kafka event to be processed and verify adjustment in forecast
        Awaitility.await().until(() -> {
            CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
            // Active month is 2022-01 (clock is set to 2022-01-01)
            CashFlowMonthlyForecast activeMonth = statement.getForecasts().get(YearMonth.of(2022, 1));
            // Check if adjustment transaction is in the ACTIVE month's inflows (as PAID transaction)
            return activeMonth.getCategorizedInFlows().stream()
                    .flatMap(c -> c.getGroupedTransactions().get(PaymentStatus.PAID).stream())
                    .anyMatch(t -> t.getName().equals(new Name("Balance Adjustment")));
        });

        // Verify the adjustment is in the correct month with correct amount
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
        CashFlowMonthlyForecast activeMonth = statement.getForecasts().get(YearMonth.of(2022, 1));

        TransactionDetails adjustmentTx = activeMonth.getCategorizedInFlows().stream()
                .flatMap(c -> c.getGroupedTransactions().get(PaymentStatus.PAID).stream())
                .filter(t -> t.getName().equals(new Name("Balance Adjustment")))
                .findFirst()
                .orElseThrow();

        assertThat(adjustmentTx.getMoney()).isEqualTo(Money.of(500, "USD"));
    }

    // ==================== ROLLBACK IMPORT TESTS ====================

    @Test
    void shouldRollbackImportAndClearAllTransactions() {
        // given - create CashFlow with history and import some transactions
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For rollback testing")
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

        // Import some transactions
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Salary 1")
                        .description("October salary")
                        .money(Money.of(3000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Rent")
                        .description("November rent")
                        .money(Money.of(1200, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-01T00:00:00Z"))
                        .build()
        );

        // Verify transactions were imported
        CashFlowDto.CashFlowSummaryJson summaryBefore = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summaryBefore.getCashChanges()).hasSize(2);
        assertThat(summaryBefore.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);

        // when - rollback import
        CashFlowDto.RollbackImportResponseJson response = cashFlowRestController.rollbackImport(
                cashFlowId,
                CashFlowDto.RollbackImportJson.builder()
                        .deleteCategories(false)
                        .build()
        );

        // then - CashFlow should be cleared but remain in SETUP
        assertThat(response.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);
        assertThat(response.isCategoriesDeleted()).isFalse();

        // Verify all transactions were deleted
        CashFlowDto.CashFlowSummaryJson summaryAfter = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summaryAfter.getCashChanges()).isEmpty();
        assertThat(summaryAfter.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);
    }

    @Test
    void shouldRollbackImportAndClearCategoriesWhenRequested() {
        // given - create CashFlow with history and add custom categories
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For rollback with categories")
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

        // Add custom categories
        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Income")
                        .type(INFLOW)
                        .build()
        );

        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .parentCategoryName("Income")
                        .category("Salary")
                        .type(INFLOW)
                        .build()
        );

        // Import transaction to custom category
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Salary")
                        .name("October salary")
                        .description("Salary")
                        .money(Money.of(3000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // Verify categories were created
        CashFlowDto.CashFlowSummaryJson summaryBefore = cashFlowRestController.getCashFlow(cashFlowId);
        // Should have: Uncategorized, Income, Salary
        assertThat(summaryBefore.getInflowCategories()).hasSizeGreaterThan(1);

        // when - rollback import with deleteCategories=true
        CashFlowDto.RollbackImportResponseJson response = cashFlowRestController.rollbackImport(
                cashFlowId,
                CashFlowDto.RollbackImportJson.builder()
                        .deleteCategories(true)
                        .build()
        );

        // then
        assertThat(response.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);
        assertThat(response.isCategoriesDeleted()).isTrue();

        // Verify categories were reset to just Uncategorized
        CashFlowDto.CashFlowSummaryJson summaryAfter = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summaryAfter.getInflowCategories()).hasSize(1);
        assertThat(summaryAfter.getInflowCategories().get(0).getCategoryName().name()).isEqualTo("Uncategorized");
        assertThat(summaryAfter.getCashChanges()).isEmpty();
    }

    @Test
    void shouldRejectRollbackOnNonSetupCashFlow() {
        // given - create CashFlow with history and attest it (making it OPEN)
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("Will be attested")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .startPeriod("2021-10")
                        .initialBalance(Money.of(1000, "USD"))
                        .build()
        );

        // Wait for CashFlow forecast to be created
        Awaitility.await().until(
                () -> statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).isPresent());

        // Attest the import to make CashFlow OPEN
        cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(1000, "USD"))
                        .forceAttestation(false)
                        .build()
        );

        // Verify CashFlow is now OPEN
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summary.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);

        // when/then - trying to rollback should fail
        assertThatThrownBy(() -> cashFlowRestController.rollbackImport(
                cashFlowId,
                CashFlowDto.RollbackImportJson.builder()
                        .deleteCategories(false)
                        .build()
        )).isInstanceOf(RollbackNotAllowedInNonSetupModeException.class)
                .hasMessageContaining("SETUP mode")
                .hasMessageContaining(cashFlowId);
    }

    @Test
    void shouldClearForecastTransactionsAfterRollback() {
        // given - create CashFlow with history and import transactions
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For forecast clearing test")
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

        // Import transactions to different months
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("October salary")
                        .description("Salary")
                        .money(Money.of(3000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("November rent")
                        .description("Rent")
                        .money(Money.of(1200, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2021-11-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-11-01T00:00:00Z"))
                        .build()
        );

        // Wait for transactions to be processed in forecast
        Awaitility.await().until(() -> {
            CashFlowForecastStatement stmt = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
            CashFlowMonthlyForecast oct = stmt.getForecasts().get(YearMonth.of(2021, 10));
            return oct.getCategorizedInFlows().stream()
                    .flatMap(c -> c.getGroupedTransactions().getTransactions().values().stream())
                    .flatMap(List::stream)
                    .count() > 0;
        });

        // Verify transactions exist in forecast before rollback
        CashFlowForecastStatement statementBefore = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
        CashFlowMonthlyForecast octBefore = statementBefore.getForecasts().get(YearMonth.of(2021, 10));
        long octTransactionsBefore = octBefore.getCategorizedInFlows().stream()
                .flatMap(c -> c.getGroupedTransactions().getTransactions().values().stream())
                .flatMap(List::stream)
                .count();
        assertThat(octTransactionsBefore).isGreaterThan(0);

        // when - rollback import
        cashFlowRestController.rollbackImport(
                cashFlowId,
                CashFlowDto.RollbackImportJson.builder()
                        .deleteCategories(false)
                        .build()
        );

        // then - wait for forecast to be cleared
        Awaitility.await().until(() -> {
            CashFlowForecastStatement stmt = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
            CashFlowMonthlyForecast oct = stmt.getForecasts().get(YearMonth.of(2021, 10));
            long transactionCount = oct.getCategorizedInFlows().stream()
                    .flatMap(c -> c.getGroupedTransactions().getTransactions().values().stream())
                    .flatMap(List::stream)
                    .count();
            return transactionCount == 0;
        });

        // Verify all IMPORT_PENDING months are cleared
        CashFlowForecastStatement statementAfter = statementRepository.findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();

        // Check October 2021
        CashFlowMonthlyForecast octAfter = statementAfter.getForecasts().get(YearMonth.of(2021, 10));
        assertThat(octAfter.getStatus()).isEqualTo(CashFlowMonthlyForecast.Status.IMPORT_PENDING);
        long octTransactionsAfter = octAfter.getCategorizedInFlows().stream()
                .flatMap(c -> c.getGroupedTransactions().getTransactions().values().stream())
                .flatMap(List::stream)
                .count();
        assertThat(octTransactionsAfter).isZero();

        // Check November 2021
        CashFlowMonthlyForecast novAfter = statementAfter.getForecasts().get(YearMonth.of(2021, 11));
        assertThat(novAfter.getStatus()).isEqualTo(CashFlowMonthlyForecast.Status.IMPORT_PENDING);
        long novOutflowsAfter = novAfter.getCategorizedOutFlows().stream()
                .flatMap(c -> c.getGroupedTransactions().getTransactions().values().stream())
                .flatMap(List::stream)
                .count();
        assertThat(novOutflowsAfter).isZero();
    }

    @Test
    void shouldAllowReImportAfterRollback() {
        // given - create CashFlow with history and import some transactions
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("userId")
                        .name("Historical Cash Flow")
                        .description("For re-import testing")
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

        // Import first batch of transactions (with "wrong" data)
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Wrong amount")
                        .description("Wrong")
                        .money(Money.of(9999, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // Rollback the import
        cashFlowRestController.rollbackImport(
                cashFlowId,
                CashFlowDto.RollbackImportJson.builder()
                        .deleteCategories(false)
                        .build()
        );

        // Verify CashFlow is still in SETUP mode and can accept new imports
        CashFlowDto.CashFlowSummaryJson summaryAfterRollback = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summaryAfterRollback.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);
        assertThat(summaryAfterRollback.getCashChanges()).isEmpty();

        // when - re-import with correct data
        String newCashChangeId = cashFlowRestController.importHistoricalCashChange(
                cashFlowId,
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category("Uncategorized")
                        .name("Correct salary")
                        .description("Correct")
                        .money(Money.of(3000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2021-10-25T00:00:00Z"))
                        .build()
        );

        // then - verify new import was successful
        assertThat(newCashChangeId).isNotBlank();

        CashFlowDto.CashFlowSummaryJson summaryAfterReImport = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summaryAfterReImport.getCashChanges()).hasSize(1);
        assertThat(summaryAfterReImport.getCashChanges().get(newCashChangeId).getName()).isEqualTo("Correct salary");
        assertThat(summaryAfterReImport.getCashChanges().get(newCashChangeId).getMoney()).isEqualTo(Money.of(3000, "USD"));

        // And verify we can still attest
        CashFlowDto.AttestHistoricalImportResponseJson attestResponse = cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(Money.of(4000, "USD"))  // 1000 initial + 3000 inflow
                        .forceAttestation(false)
                        .build()
        );

        assertThat(attestResponse.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(attestResponse.getCalculatedBalance()).isEqualTo(Money.of(4000, "USD"));
    }

    // ========== Category Archiving Tests ==========

    @Test
    void shouldArchiveUserCreatedCategory() {
        // given - create cashflow and add a user category
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // Create a user category
        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("My Category")
                        .type(OUTFLOW)
                        .build()
        );

        // Verify category was created and is not archived
        CashFlowDto.CashFlowSummaryJson summaryBeforeArchive = cashFlowRestController.getCashFlow(cashFlowId);
        Category categoryBeforeArchive = summaryBeforeArchive.getOutflowCategories().stream()
                .filter(c -> c.getCategoryName().name().equals("My Category"))
                .findFirst()
                .orElseThrow();
        assertThat(categoryBeforeArchive.isArchived()).isFalse();

        // when - archive the category
        cashFlowRestController.archiveCategory(
                cashFlowId,
                CashFlowDto.ArchiveCategoryJson.builder()
                        .categoryName("My Category")
                        .categoryType(OUTFLOW)
                        .build()
        );

        // then - verify category is archived
        CashFlowDto.CashFlowSummaryJson summaryAfterArchive = cashFlowRestController.getCashFlow(cashFlowId);
        Category categoryAfterArchive = summaryAfterArchive.getOutflowCategories().stream()
                .filter(c -> c.getCategoryName().name().equals("My Category"))
                .findFirst()
                .orElseThrow();
        assertThat(categoryAfterArchive.isArchived()).isTrue();
        assertThat(categoryAfterArchive.getValidTo()).isNotNull();

        // Wait for Kafka event processing
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains("CategoryArchivedEvent"))
                        .orElse(false));
    }

    @Test
    void shouldUnarchiveCategory() {
        // given - create cashflow, add a category, and archive it
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Archive Test Category")
                        .type(INFLOW)
                        .build()
        );

        cashFlowRestController.archiveCategory(
                cashFlowId,
                CashFlowDto.ArchiveCategoryJson.builder()
                        .categoryName("Archive Test Category")
                        .categoryType(INFLOW)
                        .build()
        );

        // Verify it's archived
        CashFlowDto.CashFlowSummaryJson summaryAfterArchive = cashFlowRestController.getCashFlow(cashFlowId);
        Category archivedCategory = summaryAfterArchive.getInflowCategories().stream()
                .filter(c -> c.getCategoryName().name().equals("Archive Test Category"))
                .findFirst()
                .orElseThrow();
        assertThat(archivedCategory.isArchived()).isTrue();

        // when - unarchive the category
        cashFlowRestController.unarchiveCategory(
                cashFlowId,
                CashFlowDto.UnarchiveCategoryJson.builder()
                        .categoryName("Archive Test Category")
                        .categoryType(INFLOW)
                        .build()
        );

        // then - verify category is unarchived
        CashFlowDto.CashFlowSummaryJson summaryAfterUnarchive = cashFlowRestController.getCashFlow(cashFlowId);
        Category unarchivedCategory = summaryAfterUnarchive.getInflowCategories().stream()
                .filter(c -> c.getCategoryName().name().equals("Archive Test Category"))
                .findFirst()
                .orElseThrow();
        assertThat(unarchivedCategory.isArchived()).isFalse();
        assertThat(unarchivedCategory.getValidTo()).isNull();

        // Wait for Kafka event processing
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains("CategoryUnarchivedEvent"))
                        .orElse(false));
    }

    @Test
    void shouldNotAllowArchivingSystemCategory() {
        // given - create cashflow (has system "Uncategorized" category)
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // when & then - trying to archive the system "Uncategorized" category should fail
        assertThatThrownBy(() -> cashFlowRestController.archiveCategory(
                cashFlowId,
                CashFlowDto.ArchiveCategoryJson.builder()
                        .categoryName("Uncategorized")
                        .categoryType(OUTFLOW)
                        .build()
        )).isInstanceOf(CannotArchiveSystemCategoryException.class);
    }

    @Test
    void shouldNotAllowArchivingNonExistentCategory() {
        // given - create cashflow
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // when & then - trying to archive non-existent category should fail
        assertThatThrownBy(() -> cashFlowRestController.archiveCategory(
                cashFlowId,
                CashFlowDto.ArchiveCategoryJson.builder()
                        .categoryName("Non Existent Category")
                        .categoryType(INFLOW)
                        .build()
        )).isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void archivedCategoryShouldRemainVisibleInExistingTransactions() {
        // given - create cashflow and add a category with a transaction
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // Create a user category
        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Rent")
                        .type(OUTFLOW)
                        .build()
        );

        // Add a transaction using the category (test clock is at 2022-01-01, active period is 2022-01)
        String cashChangeId = cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Rent")
                        .name("January rent")
                        .description("Rent payment")
                        .money(Money.of(500, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .build()
        );

        // when - archive the category
        cashFlowRestController.archiveCategory(
                cashFlowId,
                CashFlowDto.ArchiveCategoryJson.builder()
                        .categoryName("Rent")
                        .categoryType(OUTFLOW)
                        .build()
        );

        // then - the transaction should still reference the category
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        CashFlowDto.CashChangeSummaryJson transaction = summary.getCashChanges().get(cashChangeId);
        assertThat(transaction.getCategoryName()).isEqualTo("Rent");

        // And the archived category should still be in the list (for historical display)
        Category rentCategory = summary.getOutflowCategories().stream()
                .filter(c -> c.getCategoryName().name().equals("Rent"))
                .findFirst()
                .orElseThrow();
        assertThat(rentCategory.isArchived()).isTrue();
    }

    @Test
    void shouldPreserveOriginFieldOnSystemCategory() {
        // given - create cashflow
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // when - get cashflow
        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);

        // then - verify Uncategorized has SYSTEM origin
        Category uncategorizedInflow = summary.getInflowCategories().stream()
                .filter(c -> c.getCategoryName().name().equals("Uncategorized"))
                .findFirst()
                .orElseThrow();
        assertThat(uncategorizedInflow.getOrigin()).isEqualTo(CategoryOrigin.SYSTEM);
        assertThat(uncategorizedInflow.isModifiable()).isFalse();

        Category uncategorizedOutflow = summary.getOutflowCategories().stream()
                .filter(c -> c.getCategoryName().name().equals("Uncategorized"))
                .findFirst()
                .orElseThrow();
        assertThat(uncategorizedOutflow.getOrigin()).isEqualTo(CategoryOrigin.SYSTEM);
        assertThat(uncategorizedOutflow.isModifiable()).isFalse();
    }

    @Test
    void shouldNotAllowAddingExpectedCashChangeToArchivedCategory() {
        // given - create cashflow with a category and archive it
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Archived Category")
                        .type(OUTFLOW)
                        .build()
        );

        cashFlowRestController.archiveCategory(
                cashFlowId,
                CashFlowDto.ArchiveCategoryJson.builder()
                        .categoryName("Archived Category")
                        .categoryType(OUTFLOW)
                        .build()
        );

        // when & then - trying to add expected cash change to archived category should fail
        assertThatThrownBy(() -> cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Archived Category")
                        .name("Test expense")
                        .description("Test")
                        .money(Money.of(100, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-15T00:00:00Z"))
                        .build()
        )).isInstanceOf(CategoryIsArchivedException.class)
                .hasMessageContaining("Cannot add cash change to archived category [Archived Category]");
    }

    @Test
    void shouldNotAllowAddingPaidCashChangeToArchivedCategory() {
        // given - create cashflow with a category and archive it
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Archived Outflow Category")
                        .type(OUTFLOW)
                        .build()
        );

        cashFlowRestController.archiveCategory(
                cashFlowId,
                CashFlowDto.ArchiveCategoryJson.builder()
                        .categoryName("Archived Outflow Category")
                        .categoryType(OUTFLOW)
                        .build()
        );

        // when & then - trying to add paid cash change to archived category should fail
        // Note: FixedClockConfig sets clock to 2022-01-01T00:00:00Z
        ZonedDateTime paidDate = ZonedDateTime.parse("2022-01-01T00:00:00Z");
        assertThatThrownBy(() -> cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Archived Outflow Category")
                        .name("Test paid expense")
                        .description("Test")
                        .money(Money.of(50, "USD"))
                        .type(OUTFLOW)
                        .dueDate(paidDate)
                        .paidDate(paidDate)
                        .build()
        )).isInstanceOf(CategoryIsArchivedException.class)
                .hasMessageContaining("Cannot add cash change to archived category [Archived Outflow Category]");
    }

    @Test
    void shouldAllowAddingCashChangeToUnarchivedCategory() {
        // given - create cashflow with a category, archive it, then unarchive it
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("cash-flow name")
                        .description("cash-flow description")
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Unarchived Category")
                        .type(INFLOW)
                        .build()
        );

        // Archive the category
        cashFlowRestController.archiveCategory(
                cashFlowId,
                CashFlowDto.ArchiveCategoryJson.builder()
                        .categoryName("Unarchived Category")
                        .categoryType(INFLOW)
                        .build()
        );

        // Unarchive the category
        cashFlowRestController.unarchiveCategory(
                cashFlowId,
                CashFlowDto.UnarchiveCategoryJson.builder()
                        .categoryName("Unarchived Category")
                        .categoryType(INFLOW)
                        .build()
        );

        // when - add expected cash change to unarchived category (should succeed)
        String cashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Unarchived Category")
                        .name("Test income")
                        .description("Test")
                        .money(Money.of(200, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-20T00:00:00Z"))
                        .build()
        );

        // then - cash change should be created
        assertThat(cashChangeId).isNotNull();

        CashFlowDto.CashFlowSummaryJson summary = cashFlowRestController.getCashFlow(cashFlowId);
        assertThat(summary.getCashChanges()).hasSize(1);
        assertThat(summary.getCashChanges().get(cashChangeId).getName())
                .isEqualTo("Test income");
    }
}
