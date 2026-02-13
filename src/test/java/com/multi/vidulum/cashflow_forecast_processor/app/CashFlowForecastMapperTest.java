package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.TestIds;
import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CashFlowForecastMapperTest {

    private CashFlowForecastMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CashFlowForecastMapper();
    }

    @Test
    void shouldMapCashFlowForecastStatement() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        YearMonth period = YearMonth.of(2022, 1);
        ZonedDateTime now = ZonedDateTime.parse("2022-01-15T10:00:00Z");
        Checksum checksum = new Checksum("abc123checksum");

        CashFlowForecastStatement statement = CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .bankAccountNumber(BankAccountNumber.fromIban("GB29NWBK60161331926819", Currency.of("USD")))
                .categoryStructure(new CurrentCategoryStructure(
                        List.of(new CategoryNode(null, new CategoryName("Salary"), new LinkedList<>())),
                        List.of(new CategoryNode(null, new CategoryName("Rent"), new LinkedList<>())),
                        now
                ))
                .forecasts(Map.of(
                        period,
                        CashFlowMonthlyForecast.builder()
                                .period(period)
                                .cashFlowStats(new CashFlowStats(
                                        Money.of(1000, "USD"),
                                        Money.of(1500, "USD"),
                                        Money.of(500, "USD"),
                                        new CashSummary(
                                                Money.of(500, "USD"),
                                                Money.of(300, "USD"),
                                                Money.of(0, "USD")
                                        ),
                                        new CashSummary(
                                                Money.of(200, "USD"),
                                                Money.of(100, "USD"),
                                                Money.of(0, "USD")
                                        )
                                ))
                                .categorizedInFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Salary"))
                                                .category(new Category("Salary"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.of(500, "USD"))
                                                .budgeting(null)
                                                .build()
                                ))
                                .categorizedOutFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Rent"))
                                                .category(new Category("Rent"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.of(200, "USD"))
                                                .budgeting(null)
                                                .build()
                                ))
                                .status(CashFlowMonthlyForecast.Status.ACTIVE)
                                .attestation(null)
                                .build()
                ))
                .lastModification(now)
                .lastMessageChecksum(checksum)
                .build();

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson result = mapper.map(statement);

        // then
        assertThat(result.getCashFlowId()).isEqualTo(cashFlowId.id());
        assertThat(result.getBankAccountNumber().iban().value()).isEqualTo("GB29NWBK60161331926819");
        assertThat(result.getBankAccountNumber().denomination()).isEqualTo(Currency.of("USD"));
        assertThat(result.getLastModification()).isEqualTo(now);
        assertThat(result.getLastMessageChecksum()).isEqualTo("abc123checksum");

        // Verify forecasts
        assertThat(result.getForecasts()).hasSize(1);
        assertThat(result.getForecasts()).containsKey("2022-01");

        CashFlowForecastDto.CashFlowMonthlyForecastJson forecastJson = result.getForecasts().get("2022-01");
        assertThat(forecastJson.getPeriod()).isEqualTo("2022-01");
        assertThat(forecastJson.getStatus()).isEqualTo("ACTIVE");

        // Verify cash flow stats
        assertThat(forecastJson.getCashFlowStats().getStart()).isEqualTo(Money.of(1000, "USD"));
        assertThat(forecastJson.getCashFlowStats().getEnd()).isEqualTo(Money.of(1500, "USD"));
        assertThat(forecastJson.getCashFlowStats().getNetChange()).isEqualTo(Money.of(500, "USD"));

        // Verify category structure
        assertThat(result.getCategoryStructure().getInflowCategoryStructure()).hasSize(1);
        assertThat(result.getCategoryStructure().getInflowCategoryStructure().get(0).getCategoryName())
                .isEqualTo("Salary");
        assertThat(result.getCategoryStructure().getOutflowCategoryStructure()).hasSize(1);
        assertThat(result.getCategoryStructure().getOutflowCategoryStructure().get(0).getCategoryName())
                .isEqualTo("Rent");
    }

    @Test
    void shouldMapCashFlowStatsCorrectly() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        YearMonth period = YearMonth.of(2022, 3);
        ZonedDateTime now = ZonedDateTime.parse("2022-03-01T00:00:00Z");

        CashFlowStats stats = new CashFlowStats(
                Money.of(5000, "USD"),
                Money.of(7500, "USD"),
                Money.of(2500, "USD"),
                new CashSummary(
                        Money.of(3000, "USD"),
                        Money.of(1000, "USD"),
                        Money.of(500, "USD")
                ),
                new CashSummary(
                        Money.of(1500, "USD"),
                        Money.of(500, "USD"),
                        Money.of(0, "USD")
                )
        );

        CashFlowForecastStatement statement = CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .bankAccountNumber(BankAccountNumber.fromIban("GB29NWBK60161331926819", Currency.of("USD")))
                .categoryStructure(new CurrentCategoryStructure(
                        List.of(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>())),
                        List.of(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>())),
                        now
                ))
                .forecasts(Map.of(
                        period,
                        CashFlowMonthlyForecast.builder()
                                .period(period)
                                .cashFlowStats(stats)
                                .categorizedInFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Uncategorized"))
                                                .category(new Category("Uncategorized"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.zero("USD"))
                                                .build()
                                ))
                                .categorizedOutFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Uncategorized"))
                                                .category(new Category("Uncategorized"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.zero("USD"))
                                                .build()
                                ))
                                .status(CashFlowMonthlyForecast.Status.FORECASTED)
                                .build()
                ))
                .lastModification(now)
                .lastMessageChecksum(new Checksum("checksum123"))
                .build();

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson result = mapper.map(statement);

        // then
        CashFlowForecastDto.CashFlowStatsJson statsJson = result.getForecasts().get("2022-03").getCashFlowStats();
        assertThat(statsJson.getStart()).isEqualTo(Money.of(5000, "USD"));
        assertThat(statsJson.getEnd()).isEqualTo(Money.of(7500, "USD"));
        assertThat(statsJson.getNetChange()).isEqualTo(Money.of(2500, "USD"));

        assertThat(statsJson.getInflowStats().getActual()).isEqualTo(Money.of(3000, "USD"));
        assertThat(statsJson.getInflowStats().getExpected()).isEqualTo(Money.of(1000, "USD"));
        assertThat(statsJson.getInflowStats().getGapToForecast()).isEqualTo(Money.of(500, "USD"));

        assertThat(statsJson.getOutflowStats().getActual()).isEqualTo(Money.of(1500, "USD"));
        assertThat(statsJson.getOutflowStats().getExpected()).isEqualTo(Money.of(500, "USD"));
        assertThat(statsJson.getOutflowStats().getGapToForecast()).isEqualTo(Money.zero("USD"));
    }

    @Test
    void shouldMapTransactionDetailsCorrectly() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        CashChangeId cashChangeId = TestIds.nextCashChangeId();
        YearMonth period = YearMonth.of(2022, 2);
        ZonedDateTime now = ZonedDateTime.parse("2022-02-15T10:00:00Z");
        ZonedDateTime dueDate = ZonedDateTime.parse("2022-02-20T00:00:00Z");

        TransactionDetails transactionDetails = TransactionDetails.builder()
                .cashChangeId(cashChangeId)
                .name(new Name("Monthly Salary"))
                .money(Money.of(5000, "USD"))
                .created(now)
                .dueDate(dueDate)
                .endDate(null)
                .build();

        GroupedTransactions groupedTransactions = new GroupedTransactions();
        groupedTransactions.addTransaction(new Transaction(transactionDetails, PaymentStatus.EXPECTED));

        CashFlowForecastStatement statement = CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .bankAccountNumber(BankAccountNumber.fromIban("GB29NWBK60161331926819", Currency.of("USD")))
                .categoryStructure(new CurrentCategoryStructure(
                        List.of(new CategoryNode(null, new CategoryName("Salary"), new LinkedList<>())),
                        List.of(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>())),
                        now
                ))
                .forecasts(Map.of(
                        period,
                        CashFlowMonthlyForecast.builder()
                                .period(period)
                                .cashFlowStats(CashFlowStats.justBalance(Money.of(1000, "USD")))
                                .categorizedInFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Salary"))
                                                .category(new Category("Salary"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(groupedTransactions)
                                                .totalPaidValue(Money.zero("USD"))
                                                .build()
                                ))
                                .categorizedOutFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Uncategorized"))
                                                .category(new Category("Uncategorized"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.zero("USD"))
                                                .build()
                                ))
                                .status(CashFlowMonthlyForecast.Status.ACTIVE)
                                .build()
                ))
                .lastModification(now)
                .lastMessageChecksum(new Checksum("checksum"))
                .build();

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson result = mapper.map(statement);

        // then
        CashFlowForecastDto.CashCategoryJson salaryCategory =
                result.getForecasts().get("2022-02").getCategorizedInFlows().get(0);
        assertThat(salaryCategory.getCategoryName()).isEqualTo("Salary");

        List<CashFlowForecastDto.TransactionDetailsJson> expectedTransactions =
                salaryCategory.getGroupedTransactions().getTransactions().get("EXPECTED");
        assertThat(expectedTransactions).hasSize(1);

        CashFlowForecastDto.TransactionDetailsJson transactionJson = expectedTransactions.get(0);
        assertThat(transactionJson.getCashChangeId()).isEqualTo(cashChangeId.id());
        assertThat(transactionJson.getName()).isEqualTo("Monthly Salary");
        assertThat(transactionJson.getMoney()).isEqualTo(Money.of(5000, "USD"));
        assertThat(transactionJson.getCreated()).isEqualTo(now);
        assertThat(transactionJson.getDueDate()).isEqualTo(dueDate);
        assertThat(transactionJson.getEndDate()).isNull();
    }

    @Test
    void shouldMapBudgetingCorrectly() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        YearMonth period = YearMonth.of(2022, 4);
        ZonedDateTime now = ZonedDateTime.parse("2022-04-01T00:00:00Z");
        ZonedDateTime budgetCreated = ZonedDateTime.parse("2022-03-15T10:00:00Z");
        ZonedDateTime budgetUpdated = ZonedDateTime.parse("2022-03-20T10:00:00Z");

        Budgeting budgeting = new Budgeting(
                Money.of(500, "USD"),
                budgetCreated,
                budgetUpdated
        );

        CashFlowForecastStatement statement = CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .bankAccountNumber(BankAccountNumber.fromIban("GB29NWBK60161331926819", Currency.of("USD")))
                .categoryStructure(new CurrentCategoryStructure(
                        List.of(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>())),
                        List.of(new CategoryNode(null, new CategoryName("Groceries"), new LinkedList<>(), budgeting)),
                        now
                ))
                .forecasts(Map.of(
                        period,
                        CashFlowMonthlyForecast.builder()
                                .period(period)
                                .cashFlowStats(CashFlowStats.justBalance(Money.of(2000, "USD")))
                                .categorizedInFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Uncategorized"))
                                                .category(new Category("Uncategorized"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.zero("USD"))
                                                .build()
                                ))
                                .categorizedOutFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Groceries"))
                                                .category(new Category("Groceries"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.of(200, "USD"))
                                                .budgeting(budgeting)
                                                .build()
                                ))
                                .status(CashFlowMonthlyForecast.Status.ACTIVE)
                                .build()
                ))
                .lastModification(now)
                .lastMessageChecksum(new Checksum("checksum"))
                .build();

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson result = mapper.map(statement);

        // then
        // Verify budgeting in category structure
        CashFlowForecastDto.CategoryNodeJson groceriesNode =
                result.getCategoryStructure().getOutflowCategoryStructure().get(0);
        assertThat(groceriesNode.getCategoryName()).isEqualTo("Groceries");
        assertThat(groceriesNode.getBudgeting()).isNotNull();
        assertThat(groceriesNode.getBudgeting().getBudget()).isEqualTo(Money.of(500, "USD"));
        assertThat(groceriesNode.getBudgeting().getCreated()).isEqualTo(budgetCreated);
        assertThat(groceriesNode.getBudgeting().getLastUpdated()).isEqualTo(budgetUpdated);

        // Verify budgeting in forecast
        CashFlowForecastDto.CashCategoryJson groceriesCategory =
                result.getForecasts().get("2022-04").getCategorizedOutFlows().get(0);
        assertThat(groceriesCategory.getCategoryName()).isEqualTo("Groceries");
        assertThat(groceriesCategory.getBudgeting()).isNotNull();
        assertThat(groceriesCategory.getBudgeting().getBudget()).isEqualTo(Money.of(500, "USD"));
    }

    @Test
    void shouldMapAttestationCorrectly() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        YearMonth period = YearMonth.of(2022, 1);
        ZonedDateTime now = ZonedDateTime.parse("2022-02-01T00:00:00Z");
        ZonedDateTime attestationDateTime = ZonedDateTime.parse("2022-01-31T23:59:59Z");

        Attestation attestation = new Attestation(
                Money.of(1500, "USD"),
                Attestation.Type.MANUAL,
                attestationDateTime
        );

        CashFlowForecastStatement statement = CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .bankAccountNumber(BankAccountNumber.fromIban("GB29NWBK60161331926819", Currency.of("USD")))
                .categoryStructure(new CurrentCategoryStructure(
                        List.of(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>())),
                        List.of(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>())),
                        now
                ))
                .forecasts(Map.of(
                        period,
                        CashFlowMonthlyForecast.builder()
                                .period(period)
                                .cashFlowStats(CashFlowStats.justBalance(Money.of(1500, "USD")))
                                .categorizedInFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Uncategorized"))
                                                .category(new Category("Uncategorized"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.zero("USD"))
                                                .build()
                                ))
                                .categorizedOutFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Uncategorized"))
                                                .category(new Category("Uncategorized"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.zero("USD"))
                                                .build()
                                ))
                                .status(CashFlowMonthlyForecast.Status.ATTESTED)
                                .attestation(attestation)
                                .build()
                ))
                .lastModification(now)
                .lastMessageChecksum(new Checksum("checksum"))
                .build();

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson result = mapper.map(statement);

        // then
        CashFlowForecastDto.CashFlowMonthlyForecastJson forecastJson = result.getForecasts().get("2022-01");
        assertThat(forecastJson.getStatus()).isEqualTo("ATTESTED");
        assertThat(forecastJson.getAttestation()).isNotNull();
        assertThat(forecastJson.getAttestation().getBankAccountBalance()).isEqualTo(Money.of(1500, "USD"));
        assertThat(forecastJson.getAttestation().getType()).isEqualTo("MANUAL");
        assertThat(forecastJson.getAttestation().getDateTime()).isEqualTo(attestationDateTime);
    }

    @Test
    void shouldMapSubCategoriesCorrectly() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        YearMonth period = YearMonth.of(2022, 5);
        ZonedDateTime now = ZonedDateTime.parse("2022-05-01T00:00:00Z");

        // Create nested category structure
        CategoryNode bankFeesNode = new CategoryNode(null, new CategoryName("Bank fees"), new LinkedList<>());
        CategoryNode overheadCostsNode = new CategoryNode(null, new CategoryName("Overhead costs"),
                List.of(bankFeesNode));

        CashCategory bankFeesCategory = CashCategory.builder()
                .categoryName(new CategoryName("Bank fees"))
                .category(new Category("Bank fees"))
                .subCategories(new LinkedList<>())
                .groupedTransactions(new GroupedTransactions())
                .totalPaidValue(Money.of(50, "USD"))
                .build();

        CashCategory overheadCostsCategory = CashCategory.builder()
                .categoryName(new CategoryName("Overhead costs"))
                .category(new Category("Overhead costs"))
                .subCategories(List.of(bankFeesCategory))
                .groupedTransactions(new GroupedTransactions())
                .totalPaidValue(Money.of(50, "USD"))
                .build();

        CashFlowForecastStatement statement = CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .bankAccountNumber(BankAccountNumber.fromIban("GB29NWBK60161331926819", Currency.of("USD")))
                .categoryStructure(new CurrentCategoryStructure(
                        List.of(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>())),
                        List.of(overheadCostsNode),
                        now
                ))
                .forecasts(Map.of(
                        period,
                        CashFlowMonthlyForecast.builder()
                                .period(period)
                                .cashFlowStats(CashFlowStats.justBalance(Money.of(1000, "USD")))
                                .categorizedInFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Uncategorized"))
                                                .category(new Category("Uncategorized"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.zero("USD"))
                                                .build()
                                ))
                                .categorizedOutFlows(List.of(overheadCostsCategory))
                                .status(CashFlowMonthlyForecast.Status.ACTIVE)
                                .build()
                ))
                .lastModification(now)
                .lastMessageChecksum(new Checksum("checksum"))
                .build();

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson result = mapper.map(statement);

        // then
        // Verify category structure has nested nodes
        CashFlowForecastDto.CategoryNodeJson overheadNode =
                result.getCategoryStructure().getOutflowCategoryStructure().get(0);
        assertThat(overheadNode.getCategoryName()).isEqualTo("Overhead costs");
        assertThat(overheadNode.getNodes()).hasSize(1);
        assertThat(overheadNode.getNodes().get(0).getCategoryName()).isEqualTo("Bank fees");

        // Verify forecasts have nested categories
        CashFlowForecastDto.CashCategoryJson overheadCategory =
                result.getForecasts().get("2022-05").getCategorizedOutFlows().get(0);
        assertThat(overheadCategory.getCategoryName()).isEqualTo("Overhead costs");
        assertThat(overheadCategory.getSubCategories()).hasSize(1);
        assertThat(overheadCategory.getSubCategories().get(0).getCategoryName()).isEqualTo("Bank fees");
    }

    @Test
    void shouldHandleNullChecksumGracefully() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        YearMonth period = YearMonth.of(2022, 1);
        ZonedDateTime now = ZonedDateTime.parse("2022-01-01T00:00:00Z");

        CashFlowForecastStatement statement = CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .bankAccountNumber(BankAccountNumber.fromIban("GB29NWBK60161331926819", Currency.of("USD")))
                .categoryStructure(new CurrentCategoryStructure(
                        List.of(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>())),
                        List.of(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>())),
                        now
                ))
                .forecasts(Map.of(
                        period,
                        CashFlowMonthlyForecast.builder()
                                .period(period)
                                .cashFlowStats(CashFlowStats.justBalance(Money.of(1000, "USD")))
                                .categorizedInFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Uncategorized"))
                                                .category(new Category("Uncategorized"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.zero("USD"))
                                                .build()
                                ))
                                .categorizedOutFlows(List.of(
                                        CashCategory.builder()
                                                .categoryName(new CategoryName("Uncategorized"))
                                                .category(new Category("Uncategorized"))
                                                .subCategories(new LinkedList<>())
                                                .groupedTransactions(new GroupedTransactions())
                                                .totalPaidValue(Money.zero("USD"))
                                                .build()
                                ))
                                .status(CashFlowMonthlyForecast.Status.ACTIVE)
                                .build()
                ))
                .lastModification(now)
                .lastMessageChecksum(null)  // null checksum
                .build();

        // when
        CashFlowForecastDto.CashFlowForecastStatementJson result = mapper.map(statement);

        // then
        assertThat(result.getLastMessageChecksum()).isNull();
    }
}
