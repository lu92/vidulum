package com.multi.vidulum.cashflow_forecast_processor;

import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.app.CashFlowRestController;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastDto;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastRestController;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZonedDateTime;
import java.util.List;

import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;
import static org.assertj.core.api.Assertions.assertThat;

public class CashFlowForecastControllerTest extends IntegrationTest {

    @Autowired
    private CashFlowRestController cashFlowRestController;

    @Autowired
    private CashFlowForecastRestController cashFlowForecastRestController;

    @Test
    void shouldGetForecastStatementForCashFlow() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Test Cash Flow")
                        .description("Test description")
                        .bankAccount(new BankAccount(
                                new BankName("Test Bank"),
                                new BankAccountNumber("US123456789", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // Wait for the CashFlowCreatedEvent to be processed
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.CashFlowCreatedEvent.class.getSimpleName()))
                        .orElse(false));

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson forecastStatement =
                cashFlowForecastRestController.getForecastStatement(cashFlowId);

        // then
        assertThat(forecastStatement.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(forecastStatement.getBankAccountNumber().account()).isEqualTo("US123456789");
        assertThat(forecastStatement.getBankAccountNumber().denomination()).isEqualTo(Currency.of("USD"));
        assertThat(forecastStatement.getForecasts()).isNotEmpty();
        assertThat(forecastStatement.getCategoryStructure()).isNotNull();
        assertThat(forecastStatement.getCategoryStructure().getInflowCategoryStructure()).isNotEmpty();
        assertThat(forecastStatement.getCategoryStructure().getOutflowCategoryStructure()).isNotEmpty();
        assertThat(forecastStatement.getLastModification()).isNotNull();
        assertThat(forecastStatement.getLastMessageChecksum()).isNotNull();

        // Verify forecasts contain at least 12 months
        assertThat(forecastStatement.getForecasts().size()).isGreaterThanOrEqualTo(12);

        // Verify initial forecast has correct starting balance
        forecastStatement.getForecasts().values().stream()
                .filter(forecast -> "ACTIVE".equals(forecast.getStatus()))
                .findFirst()
                .ifPresent(activeForecast -> {
                    assertThat(activeForecast.getCashFlowStats().getStart()).isEqualTo(Money.of(1000, "USD"));
                });
    }

    @Test
    void shouldGetForecastStatementWithCashChanges() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Test Cash Flow with transactions")
                        .description("Test description")
                        .bankAccount(new BankAccount(
                                new BankName("Test Bank"),
                                new BankAccountNumber("US987654321", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .build()
        );

        // Add an inflow cash change
        String inflowCashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Monthly Salary")
                        .description("January salary")
                        .money(Money.of(3000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-15T00:00:00Z"))
                        .build()
        );

        // Add an outflow cash change
        String outflowCashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Rent Payment")
                        .description("Monthly rent")
                        .money(Money.of(1500, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-05T00:00:00Z"))
                        .build()
        );

        // Wait for all events to be processed
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .containsAll(List.of(
                                        CashFlowEvent.CashFlowCreatedEvent.class.getSimpleName(),
                                        CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName()
                                )))
                        .orElse(false));

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson forecastStatement =
                cashFlowForecastRestController.getForecastStatement(cashFlowId);

        // then
        assertThat(forecastStatement.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(forecastStatement.getForecasts()).isNotEmpty();

        // Find the forecast for January 2022
        CashFlowForecastDto.CashFlowMonthlyForecastJson januaryForecast =
                forecastStatement.getForecasts().get("2022-01");

        assertThat(januaryForecast).isNotNull();

        // Verify inflows contain the salary transaction
        boolean hasInflowTransaction = januaryForecast.getCategorizedInFlows().stream()
                .flatMap(category -> category.getGroupedTransactions().getTransactions().values().stream())
                .flatMap(List::stream)
                .anyMatch(tx -> tx.getCashChangeId().equals(inflowCashChangeId));
        assertThat(hasInflowTransaction).isTrue();

        // Verify outflows contain the rent transaction
        boolean hasOutflowTransaction = januaryForecast.getCategorizedOutFlows().stream()
                .flatMap(category -> category.getGroupedTransactions().getTransactions().values().stream())
                .flatMap(List::stream)
                .anyMatch(tx -> tx.getCashChangeId().equals(outflowCashChangeId));
        assertThat(hasOutflowTransaction).isTrue();
    }

    @Test
    void shouldGetForecastStatementWithBudgeting() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Budgeted Cash Flow")
                        .description("Cash flow with budgeting")
                        .bankAccount(new BankAccount(
                                new BankName("Budget Bank"),
                                new BankAccountNumber("US111222333", Currency.of("USD")),
                                Money.of(2000, "USD")))
                        .build()
        );

        // Create a category
        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Groceries")
                        .type(OUTFLOW)
                        .build()
        );

        // Set budgeting for the category
        cashFlowRestController.setBudgeting(
                CashFlowDto.SetBudgetingJson.builder()
                        .cashFlowId(cashFlowId)
                        .categoryName("Groceries")
                        .categoryType(OUTFLOW)
                        .budget(Money.of(500, "USD"))
                        .build()
        );

        // Wait for all events to be processed
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.BudgetingSetEvent.class.getSimpleName()))
                        .orElse(false));

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson forecastStatement =
                cashFlowForecastRestController.getForecastStatement(cashFlowId);

        // then
        assertThat(forecastStatement.getCashFlowId()).isEqualTo(cashFlowId);

        // Verify category structure contains budgeting
        boolean hasBudgetingInStructure = forecastStatement.getCategoryStructure()
                .getOutflowCategoryStructure().stream()
                .anyMatch(node -> "Groceries".equals(node.getCategoryName()) &&
                        node.getBudgeting() != null &&
                        node.getBudgeting().getBudget().equals(Money.of(500, "USD")));
        assertThat(hasBudgetingInStructure).isTrue();

        // Verify forecasts contain budgeting
        forecastStatement.getForecasts().values().forEach(forecast -> {
            boolean hasBudgetingInForecast = forecast.getCategorizedOutFlows().stream()
                    .anyMatch(category -> "Groceries".equals(category.getCategoryName()) &&
                            category.getBudgeting() != null &&
                            category.getBudgeting().getBudget().equals(Money.of(500, "USD")));
            assertThat(hasBudgetingInForecast).isTrue();
        });
    }

    @Test
    void shouldGetForecastStatementWithConfirmedCashChange() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Cash Flow with confirmed transaction")
                        .description("Test description")
                        .bankAccount(new BankAccount(
                                new BankName("Test Bank"),
                                new BankAccountNumber("US444555666", Currency.of("USD")),
                                Money.of(1000, "USD")))
                        .build()
        );

        // Add and confirm an inflow cash change
        String cashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Bonus")
                        .description("Year-end bonus")
                        .money(Money.of(500, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-20T00:00:00Z"))
                        .build()
        );

        cashFlowRestController.confirm(
                CashFlowDto.ConfirmCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .cashChangeId(cashChangeId)
                        .build()
        );

        // Wait for all events to be processed
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.CashChangeConfirmedEvent.class.getSimpleName()))
                        .orElse(false));

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson forecastStatement =
                cashFlowForecastRestController.getForecastStatement(cashFlowId);

        // then
        assertThat(forecastStatement.getCashFlowId()).isEqualTo(cashFlowId);

        // Find the January 2022 forecast
        CashFlowForecastDto.CashFlowMonthlyForecastJson januaryForecast =
                forecastStatement.getForecasts().get("2022-01");

        assertThat(januaryForecast).isNotNull();

        // Verify the transaction is in PAID status
        boolean hasConfirmedTransaction = januaryForecast.getCategorizedInFlows().stream()
                .flatMap(category -> {
                    List<CashFlowForecastDto.TransactionDetailsJson> paidTransactions =
                            category.getGroupedTransactions().getTransactions().get("PAID");
                    return paidTransactions != null ? paidTransactions.stream() : java.util.stream.Stream.empty();
                })
                .anyMatch(tx -> tx.getCashChangeId().equals(cashChangeId));
        assertThat(hasConfirmedTransaction).isTrue();
    }

    @Test
    void shouldHaveSynchronizedChecksumBetweenAggregateAndForecastStatement() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Checksum Sync Test")
                        .description("Testing checksum synchronization")
                        .bankAccount(new BankAccount(
                                new BankName("Sync Bank"),
                                new BankAccountNumber("US777888999", Currency.of("USD")),
                                Money.of(3000, "USD")))
                        .build()
        );

        // Add a cash change to generate more events
        String cashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Test Transaction")
                        .description("Transaction for checksum test")
                        .money(Money.of(500, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-10T00:00:00Z"))
                        .build()
        );

        // Confirm the cash change
        cashFlowRestController.confirm(
                CashFlowDto.ConfirmCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .cashChangeId(cashChangeId)
                        .build()
        );

        // Get checksum from the aggregate (via CashFlowRestController)
        CashFlowDto.CashFlowSummaryJson cashFlowSummary = cashFlowRestController.getCashFlow(cashFlowId);
        String aggregateChecksum = cashFlowSummary.getLastMessageChecksum();

        // Wait for all events to be processed by the forecast processor
        Awaitility.await().until(
                () -> {
                    CashFlowForecastDto.CashFlowForecastStatementJson statement =
                            cashFlowForecastRestController.getForecastStatement(cashFlowId);
                    return aggregateChecksum.equals(statement.getLastMessageChecksum());
                });

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson forecastStatement =
                cashFlowForecastRestController.getForecastStatement(cashFlowId);

        // then
        assertThat(forecastStatement.getLastMessageChecksum())
                .as("Checksum in ForecastStatement should match checksum in CashFlow aggregate")
                .isEqualTo(aggregateChecksum);

        assertThat(forecastStatement.getLastMessageChecksum()).isNotNull();
        assertThat(aggregateChecksum).isNotNull();
    }

    @Test
    void shouldSynchronizeChecksumAfterMultipleOperations() {
        // given
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Multi-op Checksum Test")
                        .description("Testing checksum after multiple operations")
                        .bankAccount(new BankAccount(
                                new BankName("Multi Bank"),
                                new BankAccountNumber("US111333555", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .build()
        );

        // Create category
        cashFlowRestController.createCategory(
                cashFlowId,
                CashFlowDto.CreateCategoryJson.builder()
                        .category("Salary")
                        .type(INFLOW)
                        .build()
        );

        // Add cash change
        String cashChangeId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Salary")
                        .name("Monthly Salary")
                        .description("January salary")
                        .money(Money.of(4000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-15T00:00:00Z"))
                        .build()
        );

        // Edit the cash change
        cashFlowRestController.edit(
                CashFlowDto.EditCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .cashChangeId(cashChangeId)
                        .name("Monthly Salary Updated")
                        .description("January salary updated")
                        .money(Money.of(4500, "USD"))
                        .dueDate(ZonedDateTime.parse("2022-01-16T00:00:00Z"))
                        .build()
        );

        // Set budgeting
        cashFlowRestController.setBudgeting(
                CashFlowDto.SetBudgetingJson.builder()
                        .cashFlowId(cashFlowId)
                        .categoryName("Salary")
                        .categoryType(INFLOW)
                        .budget(Money.of(5000, "USD"))
                        .build()
        );

        // Get the final checksum from aggregate
        CashFlowDto.CashFlowSummaryJson cashFlowSummary = cashFlowRestController.getCashFlow(cashFlowId);
        String aggregateChecksum = cashFlowSummary.getLastMessageChecksum();

        // Wait for all events to be processed
        Awaitility.await().until(
                () -> {
                    CashFlowForecastDto.CashFlowForecastStatementJson statement =
                            cashFlowForecastRestController.getForecastStatement(cashFlowId);
                    return aggregateChecksum.equals(statement.getLastMessageChecksum());
                });

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson forecastStatement =
                cashFlowForecastRestController.getForecastStatement(cashFlowId);

        // then
        assertThat(forecastStatement.getLastMessageChecksum())
                .as("After multiple operations, checksum should still be synchronized")
                .isEqualTo(aggregateChecksum);

        // Verify both checksums are present
        assertThat(forecastStatement.getLastMessageChecksum()).isNotNull();
        assertThat(aggregateChecksum).isNotNull();

        // Verify lastModification is also present
        assertThat(forecastStatement.getLastModification()).isNotNull();
        assertThat(cashFlowSummary.getLastModification()).isNotNull();
    }

    @Test
    void shouldGetForecastStatementWithPaidCashChange() {
        // given - FixedClockConfig sets clock to 2022-01-01
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Cash Flow with paid transaction")
                        .description("Test description")
                        .bankAccount(new BankAccount(
                                new BankName("Test Bank"),
                                new BankAccountNumber("US888999000", Currency.of("USD")),
                                Money.of(2000, "USD")))
                        .build()
        );

        // Add a paid inflow cash change (already confirmed)
        String paidInflowId = cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Paid Salary")
                        .description("Already received salary")
                        .money(Money.of(3500, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-10T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-10T00:00:00Z"))
                        .build()
        );

        // Wait for all events to be processed
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.PaidCashChangeAppendedEvent.class.getSimpleName()))
                        .orElse(false));

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson forecastStatement =
                cashFlowForecastRestController.getForecastStatement(cashFlowId);

        // then
        assertThat(forecastStatement.getCashFlowId()).isEqualTo(cashFlowId);

        // Find January 2022 forecast (clock is fixed to 2022-01-01)
        CashFlowForecastDto.CashFlowMonthlyForecastJson januaryForecast =
                forecastStatement.getForecasts().get("2022-01");

        assertThat(januaryForecast).isNotNull();

        // Verify the transaction is directly in PAID status (not EXPECTED)
        boolean hasPaidTransaction = januaryForecast.getCategorizedInFlows().stream()
                .flatMap(category -> {
                    List<CashFlowForecastDto.TransactionDetailsJson> paidTransactions =
                            category.getGroupedTransactions().getTransactions().get("PAID");
                    return paidTransactions != null ? paidTransactions.stream() : java.util.stream.Stream.empty();
                })
                .anyMatch(tx -> tx.getCashChangeId().equals(paidInflowId));
        assertThat(hasPaidTransaction).isTrue();
    }

    @Test
    void shouldGetForecastStatementWithMixedExpectedAndPaidCashChanges() {
        // given - FixedClockConfig sets clock to 2022-01-01
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Mixed Cash Flow")
                        .description("Cash flow with expected and paid transactions")
                        .bankAccount(new BankAccount(
                                new BankName("Test Bank"),
                                new BankAccountNumber("US222333444", Currency.of("USD")),
                                Money.of(5000, "USD")))
                        .build()
        );

        // Add an expected cash change
        String expectedInflowId = cashFlowRestController.appendExpectedCashChange(
                CashFlowDto.AppendExpectedCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Expected Bonus")
                        .description("Pending bonus")
                        .money(Money.of(1000, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-15T00:00:00Z"))
                        .build()
        );

        // Add a paid cash change
        String paidInflowId = cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Received Payment")
                        .description("Already received payment")
                        .money(Money.of(2500, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-10T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-10T00:00:00Z"))
                        .build()
        );

        // Wait for all events to be processed
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .containsAll(List.of(
                                        CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName(),
                                        CashFlowEvent.PaidCashChangeAppendedEvent.class.getSimpleName()
                                )))
                        .orElse(false));

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson forecastStatement =
                cashFlowForecastRestController.getForecastStatement(cashFlowId);

        // then
        CashFlowForecastDto.CashFlowMonthlyForecastJson januaryForecast =
                forecastStatement.getForecasts().get("2022-01");

        assertThat(januaryForecast).isNotNull();

        // Verify expected transaction is in EXPECTED group
        boolean hasExpectedTransaction = januaryForecast.getCategorizedInFlows().stream()
                .flatMap(category -> {
                    List<CashFlowForecastDto.TransactionDetailsJson> expectedTransactions =
                            category.getGroupedTransactions().getTransactions().get("EXPECTED");
                    return expectedTransactions != null ? expectedTransactions.stream() : java.util.stream.Stream.empty();
                })
                .anyMatch(tx -> tx.getCashChangeId().equals(expectedInflowId));
        assertThat(hasExpectedTransaction).isTrue();

        // Verify paid transaction is in PAID group
        boolean hasPaidTransaction = januaryForecast.getCategorizedInFlows().stream()
                .flatMap(category -> {
                    List<CashFlowForecastDto.TransactionDetailsJson> paidTransactions =
                            category.getGroupedTransactions().getTransactions().get("PAID");
                    return paidTransactions != null ? paidTransactions.stream() : java.util.stream.Stream.empty();
                })
                .anyMatch(tx -> tx.getCashChangeId().equals(paidInflowId));
        assertThat(hasPaidTransaction).isTrue();
    }

    @Test
    void shouldGetForecastStatementWithPaidOutflow() {
        // given - FixedClockConfig sets clock to 2022-01-01
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Cash Flow with paid outflow")
                        .description("Test description")
                        .bankAccount(new BankAccount(
                                new BankName("Test Bank"),
                                new BankAccountNumber("US555666777", Currency.of("USD")),
                                Money.of(10000, "USD")))
                        .build()
        );

        // Add a paid outflow cash change
        String paidOutflowId = cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Office Rent Paid")
                        .description("Already paid rent")
                        .money(Money.of(1500, "USD"))
                        .type(OUTFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-05T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-05T00:00:00Z"))
                        .build()
        );

        // Wait for all events to be processed
        Awaitility.await().until(
                () -> cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.PaidCashChangeAppendedEvent.class.getSimpleName()))
                        .orElse(false));

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson forecastStatement =
                cashFlowForecastRestController.getForecastStatement(cashFlowId);

        // then
        CashFlowForecastDto.CashFlowMonthlyForecastJson januaryForecast =
                forecastStatement.getForecasts().get("2022-01");

        assertThat(januaryForecast).isNotNull();

        // Verify the outflow transaction is in PAID status
        boolean hasPaidOutflow = januaryForecast.getCategorizedOutFlows().stream()
                .flatMap(category -> {
                    List<CashFlowForecastDto.TransactionDetailsJson> paidTransactions =
                            category.getGroupedTransactions().getTransactions().get("PAID");
                    return paidTransactions != null ? paidTransactions.stream() : java.util.stream.Stream.empty();
                })
                .anyMatch(tx -> tx.getCashChangeId().equals(paidOutflowId));
        assertThat(hasPaidOutflow).isTrue();
    }

    @Test
    void shouldSynchronizeChecksumAfterPaidCashChange() {
        // given - FixedClockConfig sets clock to 2022-01-01
        String cashFlowId = cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("userId")
                        .name("Paid Checksum Test")
                        .description("Testing checksum after paid cash change")
                        .bankAccount(new BankAccount(
                                new BankName("Sync Bank"),
                                new BankAccountNumber("US999888777", Currency.of("USD")),
                                Money.of(3000, "USD")))
                        .build()
        );

        // Add a paid cash change
        cashFlowRestController.appendPaidCashChange(
                CashFlowDto.AppendPaidCashChangeJson.builder()
                        .cashFlowId(cashFlowId)
                        .category("Uncategorized")
                        .name("Paid Transaction")
                        .description("Transaction for checksum test")
                        .money(Money.of(750, "USD"))
                        .type(INFLOW)
                        .dueDate(ZonedDateTime.parse("2022-01-20T00:00:00Z"))
                        .paidDate(ZonedDateTime.parse("2022-01-20T00:00:00Z"))
                        .build()
        );

        // Get checksum from the aggregate
        CashFlowDto.CashFlowSummaryJson cashFlowSummary = cashFlowRestController.getCashFlow(cashFlowId);
        String aggregateChecksum = cashFlowSummary.getLastMessageChecksum();

        // Wait for all events to be processed
        Awaitility.await().until(
                () -> {
                    CashFlowForecastDto.CashFlowForecastStatementJson statement =
                            cashFlowForecastRestController.getForecastStatement(cashFlowId);
                    return aggregateChecksum.equals(statement.getLastMessageChecksum());
                });

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson forecastStatement =
                cashFlowForecastRestController.getForecastStatement(cashFlowId);

        // then
        assertThat(forecastStatement.getLastMessageChecksum())
                .as("Checksum should be synchronized after paid cash change")
                .isEqualTo(aggregateChecksum);
    }
}
