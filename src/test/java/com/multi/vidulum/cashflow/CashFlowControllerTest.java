package com.multi.vidulum.cashflow;

import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.app.CashFlowRestController;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.*;
import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;
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
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        // when and then
        assertThat(cashFlowRestController.getCashFlow(cashFlowId))
                .usingRecursiveComparison()
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

        String cashChangeId = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
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
        assertThat(cashFlowRestController.getCashFlow(cashFlowId)).isEqualTo(
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
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
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
        assertThat(cashFlowRestController.getCashFlow(cashFlowId))
                .usingRecursiveComparison()
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
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
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
        assertThat(cashFlowRestController.getCashFlow(cashFlowId))
                .usingRecursiveComparison()
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
                        .bankAccount(new BankAccount(
                                new BankName("bank"),
                                new BankAccountNumber("account number", Currency.of("USD")),
                                Money.of(0, "USD")))
                        .build()
        );

        String cashChangeId = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
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
        assertThat(cashFlowRestController.getCashFlow(cashFlowId))
                .usingRecursiveComparison()
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
                                                CashFlowEvent.CashChangeAppendedEvent.class.getSimpleName(),
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

        String cashChangeId = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
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
        assertThat(cashFlowRestController.getCashFlow(cashFlowId)).isEqualTo(
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
                                                CashFlowEvent.CashChangeAppendedEvent.class.getSimpleName()
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

        String cashChangeId = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
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
        assertThat(cashFlowRestController.getCashFlow(cashFlowId)).isEqualTo(
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
                                                CashFlowEvent.CashChangeAppendedEvent.class.getSimpleName()
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
        String cashChangeId1 = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
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
        String cashChangeId2 = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
                        .cashFlowId(cashFlowId2)
                        .category("Revenue")
                        .name("Client Payment")
                        .description("Payment from client ABC")
                        .money(Money.of(2500, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-15T00:00:00Z"))
                        .build()
        );

        String cashChangeId3 = cashFlowRestController.appendCashChange(
                CashFlowDto.AppendCashChangeJson.builder()
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
}
