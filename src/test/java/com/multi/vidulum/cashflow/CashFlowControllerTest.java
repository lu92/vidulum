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
}
