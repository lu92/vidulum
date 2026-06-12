package com.multi.vidulum.user_financial_profile;

import com.multi.vidulum.AuthenticatedHttpIntegrationTest;
import com.multi.vidulum.bank_data_ingestion.app.BankDataIngestionHttpActor;
import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.CashCategory;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatementRepository;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowMonthlyForecast;
import com.multi.vidulum.cashflow_forecast_processor.app.CashSummary;
import com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus;
import com.multi.vidulum.cashflow_forecast_processor.app.TransactionDetails;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.user_financial_profile.app.UserFinancialProfileDto;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for VID-161 Phase 1b: self-transfer detection via the UserFinancialProfile
 * registry, propagation through the import pipeline, and routing in the forecast read model.
 *
 * <p>Assertions use {@code usingRecursiveComparison()} on whole domain objects
 * (TransactionDetails, CashSummary) — new fields are auto-detected by comparison failures.
 */
@Slf4j
public class SelfTransferDetectionIntegrationTest extends AuthenticatedHttpIntegrationTest {

    private static final String PEKAO_IBAN = "PL98124014441111001078171074";

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private UserFinancialProfileHttpActor profileActor;
    private BankDataIngestionHttpActor ingestionActor;

    @Autowired
    private CashFlowForecastStatementRepository statementRepository;

    @BeforeEach
    void setupActors() {
        registerAndAuthenticate();
        profileActor = new UserFinancialProfileHttpActor(restTemplate, port);
        profileActor.setJwtToken(accessToken);
        ingestionActor = new BankDataIngestionHttpActor(restTemplate, port);
        ingestionActor.setJwtToken(accessToken);
    }

    private String uniqueCashFlowName() {
        return "SelfTransferCF-" + COUNTER.incrementAndGet();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  T4: selfTransfer=true import lands in selfTransferOutFlows
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T4: import with selfTransfer=true routes the transaction to selfTransferOutFlows")
    void shouldRouteSelfTransferImportToSelfTransferOutflows() {
        profileActor.addAccount(new UserFinancialProfileDto.AddOwnedAccountRequest(
                PEKAO_IBAN, "PLN", "Bank Pekao", "Pekao - życie"));

        String cashFlowId = ingestionActor.createCashFlowWithHistory(
                userId, uniqueCashFlowName(),
                YearMonth.of(2021, 1),
                Money.of(0, "USD"));

        ingestionActor.createCategory(cashFlowId, "Zarządzanie kontem", Type.OUTFLOW);
        ingestionActor.createCategory(cashFlowId, "Przelewy własne", "Zarządzanie kontem", Type.OUTFLOW);

        String cashChangeId = ingestionActor.importHistoricalTransaction(
                cashFlowId,
                "Przelewy własne",
                "Lucjan Bik Pekao",
                "zycie",
                Money.of(3000, "USD"),
                Type.OUTFLOW,
                ZonedDateTime.parse("2021-01-15T00:00:00Z"),
                ZonedDateTime.parse("2021-01-15T00:00:00Z"),
                true);

        TransactionDetails expectedDetails = new TransactionDetails(
                new CashChangeId(cashChangeId),
                new com.multi.vidulum.cashflow.domain.Name("Lucjan Bik Pekao"),
                Money.of(3000, "USD"),
                null, // created — ignored
                ZonedDateTime.parse("2021-01-15T00:00:00Z"),
                ZonedDateTime.parse("2021-01-15T00:00:00Z")
        );

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TransactionDetails actual = paidTransactionInSelfTransferOutFlows(cashFlowId, YearMonth.of(2021, 1), "Przelewy własne");
            assertThat(actual)
                    .usingRecursiveComparison()
                    .ignoringFields("created")
                    .isEqualTo(expectedDetails);
        });

        assertNoSelfTransferLeakageToCategorizedOutFlows(cashFlowId, YearMonth.of(2021, 1));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  T14+T17: budget excludes self-transfers
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T14+T17: outflowStats reflects only regular expenses; self-transfer excluded from budget")
    void shouldExcludeSelfTransfersFromOutflowStats() {
        profileActor.addAccount(new UserFinancialProfileDto.AddOwnedAccountRequest(
                PEKAO_IBAN, "PLN", "Bank Pekao", "Pekao"));

        String cashFlowId = ingestionActor.createCashFlowWithHistory(
                userId, uniqueCashFlowName(),
                YearMonth.of(2021, 6),
                Money.of(0, "USD"));

        ingestionActor.createCategory(cashFlowId, "Zarządzanie kontem", Type.OUTFLOW);
        ingestionActor.createCategory(cashFlowId, "Przelewy własne", "Zarządzanie kontem", Type.OUTFLOW);
        ingestionActor.createCategory(cashFlowId, "Wydatki", Type.OUTFLOW);

        ingestionActor.importHistoricalTransaction(
                cashFlowId, "Wydatki", "Zakupy", "groceries",
                Money.of(500, "USD"), Type.OUTFLOW,
                ZonedDateTime.parse("2021-06-15T00:00:00Z"),
                ZonedDateTime.parse("2021-06-15T00:00:00Z"),
                false);

        ingestionActor.importHistoricalTransaction(
                cashFlowId, "Przelewy własne", "Lucjan Bik Pekao", "zycie",
                Money.of(3000, "USD"), Type.OUTFLOW,
                ZonedDateTime.parse("2021-06-15T00:00:00Z"),
                ZonedDateTime.parse("2021-06-15T00:00:00Z"),
                true);

        CashSummary expectedOutflowStats = new CashSummary(
                Money.of(500, "USD"),
                Money.zero("USD"),
                Money.zero("USD")
        );

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            CashSummary actual = monthlyForecast(cashFlowId, YearMonth.of(2021, 6))
                    .getCashFlowStats().getOutflowStats();
            assertThat(actual)
                    .usingRecursiveComparison()
                    .isEqualTo(expectedOutflowStats);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  T15: regular outflow path unchanged (back-compat)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T15: regular outflow with selfTransfer=false stays in categorizedOutFlows and counts to budget")
    void shouldKeepRegularOutflowInCategorizedOutflows() {
        String cashFlowId = ingestionActor.createCashFlowWithHistory(
                userId, uniqueCashFlowName(),
                YearMonth.of(2021, 3),
                Money.of(0, "USD"));

        ingestionActor.createCategory(cashFlowId, "Wydatki", Type.OUTFLOW);

        String cashChangeId = ingestionActor.importHistoricalTransaction(
                cashFlowId, "Wydatki", "Zakupy", "groceries",
                Money.of(150, "USD"), Type.OUTFLOW,
                ZonedDateTime.parse("2021-03-15T00:00:00Z"),
                ZonedDateTime.parse("2021-03-15T00:00:00Z"),
                false);

        TransactionDetails expectedDetails = new TransactionDetails(
                new CashChangeId(cashChangeId),
                new com.multi.vidulum.cashflow.domain.Name("Zakupy"),
                Money.of(150, "USD"),
                null,
                ZonedDateTime.parse("2021-03-15T00:00:00Z"),
                ZonedDateTime.parse("2021-03-15T00:00:00Z")
        );

        CashSummary expectedOutflowStats = new CashSummary(
                Money.of(150, "USD"),
                Money.zero("USD"),
                Money.zero("USD")
        );

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            CashFlowMonthlyForecast monthly = monthlyForecast(cashFlowId, YearMonth.of(2021, 3));

            // Whole-object comparison of the transaction in regular category
            TransactionDetails actualDetails = paidTransactionInCategorizedOutFlows(monthly, "Wydatki");
            assertThat(actualDetails)
                    .usingRecursiveComparison()
                    .ignoringFields("created")
                    .isEqualTo(expectedDetails);

            // Self-transfer section is empty for regular outflows
            assertThat(monthly.getSelfTransferOutFlows()).isEmpty();

            // Stats include the regular outflow
            assertThat(monthly.getCashFlowStats().getOutflowStats())
                    .usingRecursiveComparison()
                    .isEqualTo(expectedOutflowStats);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  T16: INFLOW self-transfer
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T16: INFLOW selfTransfer=true routes to selfTransferInFlows and excludes from inflow budget")
    void shouldRouteInflowSelfTransferToSelfTransferInflows() {
        profileActor.addAccount(new UserFinancialProfileDto.AddOwnedAccountRequest(
                PEKAO_IBAN, "PLN", "Bank Pekao", "Pekao"));

        String cashFlowId = ingestionActor.createCashFlowWithHistory(
                userId, uniqueCashFlowName(),
                YearMonth.of(2021, 4),
                Money.of(0, "USD"));

        ingestionActor.createCategory(cashFlowId, "Zarządzanie kontem", Type.INFLOW);
        ingestionActor.createCategory(cashFlowId, "Przelewy własne", "Zarządzanie kontem", Type.INFLOW);

        String cashChangeId = ingestionActor.importHistoricalTransaction(
                cashFlowId, "Przelewy własne", "Transfer from Pekao", "rebalance",
                Money.of(2000, "USD"), Type.INFLOW,
                ZonedDateTime.parse("2021-04-10T00:00:00Z"),
                ZonedDateTime.parse("2021-04-10T00:00:00Z"),
                true);

        TransactionDetails expectedDetails = new TransactionDetails(
                new CashChangeId(cashChangeId),
                new com.multi.vidulum.cashflow.domain.Name("Transfer from Pekao"),
                Money.of(2000, "USD"),
                null,
                ZonedDateTime.parse("2021-04-10T00:00:00Z"),
                ZonedDateTime.parse("2021-04-10T00:00:00Z")
        );

        CashSummary expectedInflowStats = new CashSummary(
                Money.zero("USD"),
                Money.zero("USD"),
                Money.zero("USD")
        );

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            CashFlowMonthlyForecast monthly = monthlyForecast(cashFlowId, YearMonth.of(2021, 4));

            TransactionDetails actualDetails = paidTransactionInSelfTransferInFlows(monthly, "Przelewy własne");
            assertThat(actualDetails)
                    .usingRecursiveComparison()
                    .ignoringFields("created")
                    .isEqualTo(expectedDetails);

            assertThat(monthly.getCashFlowStats().getInflowStats())
                    .usingRecursiveComparison()
                    .isEqualTo(expectedInflowStats);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  T22: locate() returns location with selfTransfer=true for self-transfer
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T22: statement.locate() returns CashChangeLocation with selfTransfer=true for entries in self-transfer section")
    void shouldLocateSelfTransferEntriesWithFlag() {
        String cashFlowId = ingestionActor.createCashFlowWithHistory(
                userId, uniqueCashFlowName(),
                YearMonth.of(2021, 5),
                Money.of(0, "USD"));

        ingestionActor.createCategory(cashFlowId, "Zarządzanie kontem", Type.OUTFLOW);
        ingestionActor.createCategory(cashFlowId, "Przelewy własne", "Zarządzanie kontem", Type.OUTFLOW);

        String cashChangeId = ingestionActor.importHistoricalTransaction(
                cashFlowId, "Przelewy własne", "self-transfer", "test",
                Money.of(100, "USD"), Type.OUTFLOW,
                ZonedDateTime.parse("2021-05-20T00:00:00Z"),
                ZonedDateTime.parse("2021-05-20T00:00:00Z"),
                true);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            CashFlowForecastStatement statement = statementRepository
                    .findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
            CashFlowMonthlyForecast.CashChangeLocation actual = statement.locate(new CashChangeId(cashChangeId)).orElseThrow();

            assertThat(actual.selfTransfer()).isTrue();
            assertThat(actual.yearMonth()).isEqualTo(YearMonth.of(2021, 5));
            assertThat(actual.type()).isEqualTo(Type.OUTFLOW);
            assertThat(actual.categoryName()).isEqualTo(new CategoryName("Przelewy własne"));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  E5: IBAN normalization at profile entry
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("E5: IBAN with spaces/case is normalized when stored in profile (matching during detection is case-sensitive on normalized form)")
    void shouldNormalizeIbanWhenStoredInProfile() {
        profileActor.addAccount(new UserFinancialProfileDto.AddOwnedAccountRequest(
                "pl98 1240 1444 1111 0010 7817 1074", "PLN", "Bank Pekao", "Pekao"));

        UserFinancialProfileDto.OwnedAccountJson expected = new UserFinancialProfileDto.OwnedAccountJson(
                PEKAO_IBAN,
                "PLN",
                "Bank Pekao",
                "Pekao",
                "ACTIVE",
                "MANUAL",
                null,
                null,
                null
        );

        UserFinancialProfileDto.OwnedAccountsListJson list = profileActor.listAccounts();
        assertThat(list.getAccounts())
                .singleElement()
                .usingRecursiveComparison()
                .ignoringFields("addedAt")
                .isEqualTo(expected);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers — encapsulate forecast read-model traversal so test bodies
    //  focus on the assertion subject.
    // ─────────────────────────────────────────────────────────────────────

    private CashFlowMonthlyForecast monthlyForecast(String cashFlowId, YearMonth period) {
        CashFlowForecastStatement statement = statementRepository
                .findByCashFlowId(new CashFlowId(cashFlowId)).orElseThrow();
        CashFlowMonthlyForecast monthly = statement.getForecasts().get(period);
        if (monthly == null) {
            throw new IllegalStateException("Forecast missing month " + period);
        }
        return monthly;
    }

    private TransactionDetails paidTransactionInSelfTransferOutFlows(String cashFlowId, YearMonth period, String categoryName) {
        CashFlowMonthlyForecast monthly = monthlyForecast(cashFlowId, period);
        CashCategory cat = monthly.getSelfTransferOutFlows().stream()
                .filter(c -> c.getCategoryName().name().equals(categoryName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Self-transfer outflow category not found: " + categoryName));
        List<TransactionDetails> paid = cat.getGroupedTransactions().getTransactions().get(PaymentStatus.PAID);
        if (paid == null || paid.isEmpty()) {
            throw new IllegalStateException("No PAID transactions in self-transfer outflow category " + categoryName);
        }
        return paid.get(0);
    }

    private TransactionDetails paidTransactionInSelfTransferInFlows(CashFlowMonthlyForecast monthly, String categoryName) {
        CashCategory cat = monthly.getSelfTransferInFlows().stream()
                .filter(c -> c.getCategoryName().name().equals(categoryName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Self-transfer inflow category not found: " + categoryName));
        List<TransactionDetails> paid = cat.getGroupedTransactions().getTransactions().get(PaymentStatus.PAID);
        if (paid == null || paid.isEmpty()) {
            throw new IllegalStateException("No PAID transactions in self-transfer inflow category " + categoryName);
        }
        return paid.get(0);
    }

    private TransactionDetails paidTransactionInCategorizedOutFlows(CashFlowMonthlyForecast monthly, String categoryName) {
        CashCategory cat = monthly.getCategorizedOutFlows().stream()
                .filter(c -> c.getCategoryName().name().equals(categoryName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Categorized outflow category not found: " + categoryName));
        List<TransactionDetails> paid = cat.getGroupedTransactions().getTransactions().get(PaymentStatus.PAID);
        if (paid == null || paid.isEmpty()) {
            throw new IllegalStateException("No PAID transactions in categorized outflow category " + categoryName);
        }
        return paid.get(0);
    }

    private void assertNoSelfTransferLeakageToCategorizedOutFlows(String cashFlowId, YearMonth period) {
        CashFlowMonthlyForecast monthly = monthlyForecast(cashFlowId, period);
        assertThat(monthly.getCategorizedOutFlows())
                .as("Self-transfer must not appear in regular categorized outflows for period %s", period)
                .noneMatch(cat -> cat.getCategoryName().name().equals("Przelewy własne"));
    }
}
