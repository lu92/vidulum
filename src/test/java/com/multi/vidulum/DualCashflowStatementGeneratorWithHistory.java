package com.multi.vidulum;

import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.app.CashFlowRestController;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow_forecast_processor.app.CashCategory;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastDto;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastRestController;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowMonthlyForecast;
import com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.trading.domain.IntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Generator for dual cashflows with historical data import support.
 * Creates Home and Business budgets with historical transactions imported via SETUP mode,
 * then attests the import and continues with regular transaction generation.
 */
@Slf4j
public class DualCashflowStatementGeneratorWithHistory extends IntegrationTest {

    @Autowired
    private DualBudgetActor actor;

    @Autowired
    private CashFlowRestController cashFlowRestController;

    @Autowired
    private CashFlowForecastRestController cashFlowForecastRestController;

    @Autowired
    private Clock clock;

    private static final int HISTORICAL_MONTHS = 6;
    private static final int FUTURE_MONTHS = 12;
    private static final int ATTESTED_MONTHS_OFFSET = 6;
    private static final String USER_ID = "dual-budget-with-history-user";

    @Test
    public void generateDualCashflowsWithHistory() {
        // FixedClockConfig sets clock to 2022-01-01T00:00:00Z
        // We'll simulate being in the middle of a month (15th day)
        // startPeriod will be 6 months before current month

        YearMonth activePeriod = YearMonth.now(clock); // 2022-01
        YearMonth startPeriod = activePeriod.minusMonths(HISTORICAL_MONTHS); // 2021-07

        log.info("Generating dual cashflows with history");
        log.info("  Active period: {}", activePeriod);
        log.info("  Start period (historical): {}", startPeriod);
        log.info("  Historical months: {}", HISTORICAL_MONTHS);
        log.info("  Future months: {}", FUTURE_MONTHS);

        // Create Home Budget CashFlow with history support
        CashFlowId homeCashFlowId = createHomeBudgetCashFlowWithHistory(USER_ID, startPeriod);
        log.info("Created home budget cashflow with history: {}", homeCashFlowId);

        // Create Business Budget CashFlow with history support
        CashFlowId businessCashFlowId = createBusinessBudgetCashFlowWithHistory(USER_ID, startPeriod);
        log.info("Created business budget cashflow with history: {}", businessCashFlowId);

        // Wait for initial forecast creation
        await().until(() -> statementRepository.findByCashFlowId(homeCashFlowId).isPresent());
        await().until(() -> statementRepository.findByCashFlowId(businessCashFlowId).isPresent());

        // Setup categories for both cashflows
        setupHomeBudgetCategories(homeCashFlowId);
        setupBusinessBudgetCategories(businessCashFlowId);

        Random random = new Random(42); // Fixed seed for reproducibility

        // Track imported transaction totals for balance calculation
        Money homeImportedBalance = Money.of(10000, "USD"); // Initial balance
        Money businessImportedBalance = Money.of(50000, "USD"); // Initial balance

        // === PHASE 1: Import historical data ===
        log.info("=== PHASE 1: Importing historical data ===");

        for (int monthOffset = 0; monthOffset < HISTORICAL_MONTHS; monthOffset++) {
            YearMonth historicalPeriod = startPeriod.plusMonths(monthOffset);
            log.info("Importing historical data for month: {}", historicalPeriod);

            // Import home budget historical transactions
            Money homeMonthBalance = importHomeHistoricalTransactions(homeCashFlowId, historicalPeriod, random);
            homeImportedBalance = homeImportedBalance.plus(homeMonthBalance);

            // Import business budget historical transactions
            Money businessMonthBalance = importBusinessHistoricalTransactions(businessCashFlowId, historicalPeriod, random);
            businessImportedBalance = businessImportedBalance.plus(businessMonthBalance);
        }

        // Import transactions from the last historical month (just before the active month)
        // We use the last day of the previous month at various times to simulate recent historical data
        YearMonth lastHistoricalMonth = activePeriod.minusMonths(1);
        ZonedDateTime lastHistoricalDay = lastHistoricalMonth.atEndOfMonth().atTime(15, 30).atZone(ZoneOffset.UTC);
        log.info("Importing late-month transactions for: {}", lastHistoricalDay);

        // Import a few transactions from the end of the last historical month
        Money homeLateMonthBalance = importHomeLateMonthTransactions(homeCashFlowId, lastHistoricalDay, random);
        homeImportedBalance = homeImportedBalance.plus(homeLateMonthBalance);

        Money businessLateMonthBalance = importBusinessLateMonthTransactions(businessCashFlowId, lastHistoricalDay, random);
        businessImportedBalance = businessImportedBalance.plus(businessLateMonthBalance);

        log.info("Total home imported balance: {}", homeImportedBalance);
        log.info("Total business imported balance: {}", businessImportedBalance);

        // === PHASE 2: Attest historical import ===
        log.info("=== PHASE 2: Attesting historical import ===");

        // Wait 200ms before attestation as requested
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Attest home budget import
        CashFlowDto.AttestHistoricalImportResponseJson homeAttestResponse = cashFlowRestController.attestHistoricalImport(
                homeCashFlowId.id(),
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(homeImportedBalance)
                        .forceAttestation(false)
                        .build()
        );
        log.info("Home budget attestation: status={}, calculatedBalance={}, confirmedBalance={}",
                homeAttestResponse.getStatus(), homeAttestResponse.getCalculatedBalance(), homeAttestResponse.getConfirmedBalance());
        assertThat(homeAttestResponse.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);

        // Attest business budget import
        CashFlowDto.AttestHistoricalImportResponseJson businessAttestResponse = cashFlowRestController.attestHistoricalImport(
                businessCashFlowId.id(),
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(businessImportedBalance)
                        .forceAttestation(false)
                        .build()
        );
        log.info("Business budget attestation: status={}, calculatedBalance={}, confirmedBalance={}",
                businessAttestResponse.getStatus(), businessAttestResponse.getCalculatedBalance(), businessAttestResponse.getConfirmedBalance());
        assertThat(businessAttestResponse.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);

        // === PHASE 3: Generate regular transactions (current and future months) ===
        log.info("=== PHASE 3: Generating regular transactions ===");

        int totalMonths = 1 + FUTURE_MONTHS; // Current month + future months
        int numberOfAttestedMonths = totalMonths - ATTESTED_MONTHS_OFFSET;

        CashChangeId homeLastCashChangeId = null;
        PaymentStatus homeLastPaymentStatus = PaymentStatus.EXPECTED;
        YearMonth homeLastPeriod = activePeriod;

        CashChangeId businessLastCashChangeId = null;
        PaymentStatus businessLastPaymentStatus = PaymentStatus.EXPECTED;
        YearMonth businessLastPeriod = activePeriod;

        for (int monthOffset = 0; monthOffset < totalMonths; monthOffset++) {
            YearMonth currentPeriod = activePeriod.plusMonths(monthOffset);
            boolean isAttestedMonth = monthOffset < numberOfAttestedMonths;

            log.info("Processing month: {} (attested: {})", currentPeriod, isAttestedMonth);

            // Generate Home Budget Transactions
            var homeResult = generateHomeBudgetTransactions(homeCashFlowId, currentPeriod, isAttestedMonth, random);
            homeLastCashChangeId = homeResult.lastCashChangeId;
            homeLastPaymentStatus = homeResult.lastPaymentStatus;
            homeLastPeriod = currentPeriod;

            // Generate Business Budget Transactions
            var businessResult = generateBusinessBudgetTransactions(businessCashFlowId, currentPeriod, isAttestedMonth, random);
            businessLastCashChangeId = businessResult.lastCashChangeId;
            businessLastPaymentStatus = businessResult.lastPaymentStatus;
            businessLastPeriod = currentPeriod;

            // Attest the month if needed
            if (isAttestedMonth) {
                YearMonth attestPeriod = currentPeriod.plusMonths(1);
                actor.attestMonth(homeCashFlowId, attestPeriod, Money.zero("USD"), attestPeriod.atEndOfMonth().atStartOfDay(ZoneOffset.UTC));
                actor.attestMonth(businessCashFlowId, attestPeriod, Money.zero("USD"), attestPeriod.atEndOfMonth().atStartOfDay(ZoneOffset.UTC));

                // Wait for attestation events to be processed before next iteration
                YearMonth periodToCheck = currentPeriod;
                await().atMost(10, SECONDS).until(() -> {
                    CashFlowForecastStatement homeStatement = statementRepository.findByCashFlowId(homeCashFlowId).orElse(null);
                    CashFlowForecastStatement businessStatement = statementRepository.findByCashFlowId(businessCashFlowId).orElse(null);
                    if (homeStatement == null || businessStatement == null) return false;
                    CashFlowMonthlyForecast homeForecast = homeStatement.getForecasts().get(periodToCheck);
                    CashFlowMonthlyForecast businessForecast = businessStatement.getForecasts().get(periodToCheck);
                    return homeForecast != null && homeForecast.getStatus() == CashFlowMonthlyForecast.Status.ATTESTED
                            && businessForecast != null && businessForecast.getStatus() == CashFlowMonthlyForecast.Status.ATTESTED;
                });
            }
        }

        // Wait for processing to complete
        CashChangeId finalHomeLastCashChangeId = homeLastCashChangeId;
        PaymentStatus finalHomeLastPaymentStatus = homeLastPaymentStatus;
        YearMonth finalHomeLastPeriod = homeLastPeriod;
        await().atMost(30, SECONDS).until(() -> cashChangeInStatusHasBeenProcessed(homeCashFlowId, finalHomeLastPeriod, finalHomeLastCashChangeId, finalHomeLastPaymentStatus));

        CashChangeId finalBusinessLastCashChangeId = businessLastCashChangeId;
        PaymentStatus finalBusinessLastPaymentStatus = businessLastPaymentStatus;
        YearMonth finalBusinessLastPeriod = businessLastPeriod;
        await().atMost(30, SECONDS).until(() -> cashChangeInStatusHasBeenProcessed(businessCashFlowId, finalBusinessLastPeriod, finalBusinessLastCashChangeId, finalBusinessLastPaymentStatus));

        log.info("Generated home cashflow statement: {}", JsonContent.asJson(statementRepository.findByCashFlowId(homeCashFlowId).get()).content());
        log.info("Generated business cashflow statement: {}", JsonContent.asJson(statementRepository.findByCashFlowId(businessCashFlowId).get()).content());

        // Verify via /viaUser/{userId} endpoint
        List<CashFlowDto.CashFlowDetailJson> userCashFlows = cashFlowRestController.getDetailsOfCashFlowViaUser(USER_ID);

        assertThat(userCashFlows).hasSize(2);
        assertThat(userCashFlows)
                .anySatisfy(detail -> {
                    assertThat(detail.getCashFlowId()).isEqualTo(homeCashFlowId.id());
                    assertThat(detail.getUserId()).isEqualTo(USER_ID);
                    assertThat(detail.getName()).isEqualTo("Home Budget With History");
                    assertThat(detail.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
                });
        assertThat(userCashFlows)
                .anySatisfy(detail -> {
                    assertThat(detail.getCashFlowId()).isEqualTo(businessCashFlowId.id());
                    assertThat(detail.getUserId()).isEqualTo(USER_ID);
                    assertThat(detail.getName()).isEqualTo("Business Budget With History");
                    assertThat(detail.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
                });

        log.info("Successfully verified 2 cashflows for user {} via /viaUser endpoint", USER_ID);

        // Verify forecast statements
        CashFlowForecastDto.CashFlowForecastStatementJson homeForecastStatement =
                cashFlowForecastRestController.getForecastStatement(homeCashFlowId.id());
        CashFlowForecastDto.CashFlowForecastStatementJson businessForecastStatement =
                cashFlowForecastRestController.getForecastStatement(businessCashFlowId.id());

        assertThat(homeForecastStatement).isNotNull();
        assertThat(homeForecastStatement.getCashFlowId()).isEqualTo(homeCashFlowId.id());
        assertThat(homeForecastStatement.getForecasts()).isNotEmpty();

        assertThat(businessForecastStatement).isNotNull();
        assertThat(businessForecastStatement.getCashFlowId()).isEqualTo(businessCashFlowId.id());
        assertThat(businessForecastStatement.getForecasts()).isNotEmpty();

        // Verify historical months are IMPORTED (not IMPORT_PENDING)
        CashFlowForecastStatement homeStatement = statementRepository.findByCashFlowId(homeCashFlowId).orElseThrow();
        for (int i = 0; i < HISTORICAL_MONTHS; i++) {
            YearMonth historicalMonth = startPeriod.plusMonths(i);
            assertThat(homeStatement.getForecasts().get(historicalMonth).getStatus())
                    .as("Historical month %s should be IMPORTED", historicalMonth)
                    .isEqualTo(com.multi.vidulum.cashflow_forecast_processor.app.CashFlowMonthlyForecast.Status.IMPORTED);
        }

        log.info("Home forecast statement retrieved successfully:");
        log.info("  - CashFlowId: {}", homeForecastStatement.getCashFlowId());
        log.info("  - Number of forecast months: {}", homeForecastStatement.getForecasts().size());
        log.info("  - Historical months (IMPORTED): {}", HISTORICAL_MONTHS);

        log.info("Business forecast statement retrieved successfully:");
        log.info("  - CashFlowId: {}", businessForecastStatement.getCashFlowId());
        log.info("  - Number of forecast months: {}", businessForecastStatement.getForecasts().size());

        // Count total transactions
        long homeTransactionCount = homeForecastStatement.getForecasts().values().stream()
                .flatMap(forecast -> Stream.concat(
                        forecast.getCategorizedInFlows().stream(),
                        forecast.getCategorizedOutFlows().stream()))
                .flatMap(category -> category.getGroupedTransactions().getTransactions().values().stream())
                .mapToLong(List::size)
                .sum();

        long businessTransactionCount = businessForecastStatement.getForecasts().values().stream()
                .flatMap(forecast -> Stream.concat(
                        forecast.getCategorizedInFlows().stream(),
                        forecast.getCategorizedOutFlows().stream()))
                .flatMap(category -> category.getGroupedTransactions().getTransactions().values().stream())
                .mapToLong(List::size)
                .sum();

        log.info("Total transactions in forecast statements:");
        log.info("  - Home: {} transactions (including {} imported)", homeTransactionCount, "historical");
        log.info("  - Business: {} transactions (including {} imported)", businessTransactionCount, "historical");

        // Save responses to JSON files for mockup data
        Path outputDir = Path.of("src/test/java/com/multi/vidulum");

        Path homeForecastFile = outputDir.resolve("home_forecast_statement_with_history.json");
        Path businessForecastFile = outputDir.resolve("business_forecast_statement_with_history.json");
        Path userCashFlowsFile = outputDir.resolve("user_cashflows_with_history_response.json");

        try {
            Files.writeString(homeForecastFile, JsonContent.asJson(homeForecastStatement).content());
            Files.writeString(businessForecastFile, JsonContent.asJson(businessForecastStatement).content());
            Files.writeString(userCashFlowsFile, JsonContent.asJson(userCashFlows).content());

            log.info("=".repeat(80));
            log.info("Generated JSON mockup files with historical data:");
            log.info("  1. Home Forecast Statement: {}", homeForecastFile.toAbsolutePath());
            log.info("  2. Business Forecast Statement: {}", businessForecastFile.toAbsolutePath());
            log.info("  3. User CashFlows Response: {}", userCashFlowsFile.toAbsolutePath());
            log.info("=".repeat(80));
        } catch (IOException e) {
            log.error("Failed to write JSON files", e);
            throw new RuntimeException("Failed to write JSON mockup files", e);
        }

        log.info("DualCashflowStatementGeneratorWithHistory completed successfully!");
    }

    private CashFlowId createHomeBudgetCashFlowWithHistory(String userId, YearMonth startPeriod) {
        return new CashFlowId(cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId(userId)
                        .name("Home Budget With History")
                        .description("Personal home budget with historical data import")
                        .bankAccount(new BankAccount(
                                new BankName("Chase Bank"),
                                new BankAccountNumber("US12345678901234567890", Currency.of("USD")),
                                Money.of(10000, "USD")))
                        .startPeriod(startPeriod.toString())
                        .initialBalance(Money.of(10000, "USD"))
                        .build()
        ));
    }

    private CashFlowId createBusinessBudgetCashFlowWithHistory(String userId, YearMonth startPeriod) {
        return new CashFlowId(cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId(userId)
                        .name("Business Budget With History")
                        .description("Business budget with historical data import")
                        .bankAccount(new BankAccount(
                                new BankName("Bank of America"),
                                new BankAccountNumber("US98765432109876543210", Currency.of("USD")),
                                Money.of(50000, "USD")))
                        .startPeriod(startPeriod.toString())
                        .initialBalance(Money.of(50000, "USD"))
                        .build()
        ));
    }

    /**
     * Import historical transactions for home budget in a given month.
     * Returns the net balance change (inflows - outflows).
     */
    private Money importHomeHistoricalTransactions(CashFlowId cashFlowId, YearMonth period, Random random) {
        Money netBalance = Money.zero("USD");

        // Salary - main income
        if (random.nextDouble() < 0.95) {
            Money salary = Money.of(4500 + random.nextInt(1000), "USD");
            ZonedDateTime paidDate = period.atDay(10).atStartOfDay(ZoneOffset.UTC);
            importHistoricalTransaction(cashFlowId, "Salary", INFLOW, salary, paidDate);
            netBalance = netBalance.plus(salary);
        }

        // Rent - always present
        Money rent = Money.of(1200 + random.nextInt(300), "USD");
        ZonedDateTime rentDate = period.atDay(1).atStartOfDay(ZoneOffset.UTC);
        importHistoricalTransaction(cashFlowId, "Rent", OUTFLOW, rent, rentDate);
        netBalance = netBalance.minus(rent);

        // Groceries - multiple transactions
        int groceryCount = 3 + random.nextInt(5);
        for (int i = 0; i < groceryCount; i++) {
            Money grocery = Money.of(30 + random.nextInt(100), "USD");
            ZonedDateTime groceryDate = period.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            importHistoricalTransaction(cashFlowId, "Groceries", OUTFLOW, grocery, groceryDate);
            netBalance = netBalance.minus(grocery);
        }

        // Utilities
        for (String utility : List.of("Electricity", "Gas", "Internet")) {
            Money amount = Money.of(utility.equals("Internet") ? 50 + random.nextInt(30) : 60 + random.nextInt(80), "USD");
            ZonedDateTime utilityDate = period.atDay(5 + random.nextInt(10)).atStartOfDay(ZoneOffset.UTC);
            importHistoricalTransaction(cashFlowId, utility, OUTFLOW, amount, utilityDate);
            netBalance = netBalance.minus(amount);
        }

        // Fuel
        int fuelCount = 2 + random.nextInt(3);
        for (int i = 0; i < fuelCount; i++) {
            Money fuel = Money.of(40 + random.nextInt(50), "USD");
            ZonedDateTime fuelDate = period.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            importHistoricalTransaction(cashFlowId, "Fuel", OUTFLOW, fuel, fuelDate);
            netBalance = netBalance.minus(fuel);
        }

        return netBalance;
    }

    /**
     * Import historical transactions for business budget in a given month.
     * Returns the net balance change (inflows - outflows).
     */
    private Money importBusinessHistoricalTransactions(CashFlowId cashFlowId, YearMonth period, Random random) {
        Money netBalance = Money.zero("USD");

        // Product Sales - multiple
        int salesCount = 3 + random.nextInt(8);
        for (int i = 0; i < salesCount; i++) {
            Money sale = Money.of(500 + random.nextInt(4000), "USD");
            ZonedDateTime saleDate = period.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            importHistoricalTransaction(cashFlowId, "Product Sales", INFLOW, sale, saleDate);
            netBalance = netBalance.plus(sale);
        }

        // Service Revenue
        int serviceCount = 1 + random.nextInt(4);
        for (int i = 0; i < serviceCount; i++) {
            Money service = Money.of(1000 + random.nextInt(8000), "USD");
            ZonedDateTime serviceDate = period.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            importHistoricalTransaction(cashFlowId, "Service Revenue", INFLOW, service, serviceDate);
            netBalance = netBalance.plus(service);
        }

        // Office Rent - always
        Money rent = Money.of(2500 + random.nextInt(800), "USD");
        ZonedDateTime rentDate = period.atDay(1).atStartOfDay(ZoneOffset.UTC);
        importHistoricalTransaction(cashFlowId, "Office Rent", OUTFLOW, rent, rentDate);
        netBalance = netBalance.minus(rent);

        // Salaries - always
        Money salaries = Money.of(15000 + random.nextInt(8000), "USD");
        ZonedDateTime salaryDate = period.atDay(25).atStartOfDay(ZoneOffset.UTC);
        importHistoricalTransaction(cashFlowId, "Salaries", OUTFLOW, salaries, salaryDate);
        netBalance = netBalance.minus(salaries);

        // Software Subscriptions
        Money software = Money.of(500 + random.nextInt(1200), "USD");
        ZonedDateTime softwareDate = period.atDay(1).atStartOfDay(ZoneOffset.UTC);
        importHistoricalTransaction(cashFlowId, "Software Subscriptions", OUTFLOW, software, softwareDate);
        netBalance = netBalance.minus(software);

        // Cloud Services
        Money cloud = Money.of(300 + random.nextInt(1500), "USD");
        ZonedDateTime cloudDate = period.atDay(1).atStartOfDay(ZoneOffset.UTC);
        importHistoricalTransaction(cashFlowId, "Cloud Services", OUTFLOW, cloud, cloudDate);
        netBalance = netBalance.minus(cloud);

        return netBalance;
    }

    /**
     * Import transactions from the end of the last historical month.
     * These represent payments made at the end of the month, close to but before the active period.
     */
    private Money importHomeLateMonthTransactions(CashFlowId cashFlowId, ZonedDateTime lastHistoricalDay, Random random) {
        Money netBalance = Money.zero("USD");

        // End-of-month grocery shopping
        Money grocery = Money.of(80 + random.nextInt(50), "USD");
        importHistoricalTransaction(cashFlowId, "Groceries", OUTFLOW, grocery, lastHistoricalDay.minusHours(4));
        netBalance = netBalance.minus(grocery);

        // Public transit for end of month
        Money transit = Money.of(5 + random.nextInt(10), "USD");
        importHistoricalTransaction(cashFlowId, "Public Transit", OUTFLOW, transit, lastHistoricalDay.minusHours(2));
        netBalance = netBalance.minus(transit);

        return netBalance;
    }

    /**
     * Import business late-month transactions.
     */
    private Money importBusinessLateMonthTransactions(CashFlowId cashFlowId, ZonedDateTime lastHistoricalDay, Random random) {
        Money netBalance = Money.zero("USD");

        // IT Support maintenance
        Money itSupport = Money.of(100 + random.nextInt(200), "USD");
        importHistoricalTransaction(cashFlowId, "IT Support", OUTFLOW, itSupport, lastHistoricalDay.minusHours(1));
        netBalance = netBalance.minus(itSupport);

        return netBalance;
    }

    private void importHistoricalTransaction(CashFlowId cashFlowId, String category, Type type, Money money, ZonedDateTime paidDate) {
        cashFlowRestController.importHistoricalCashChange(
                cashFlowId.id(),
                CashFlowDto.ImportHistoricalCashChangeJson.builder()
                        .category(category)
                        .name("Historical: " + category)
                        .description("Imported historical transaction for " + category)
                        .money(money)
                        .type(type)
                        .dueDate(paidDate)
                        .paidDate(paidDate)
                        .build()
        );
    }

    private void setupHomeBudgetCategories(CashFlowId cashFlowId) {
        // INFLOW categories
        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Income"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Income"), new CategoryName("Salary"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Income"), new CategoryName("Bonus"), INFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Other Income"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Other Income"), new CategoryName("Refunds"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Other Income"), new CategoryName("Gifts"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Other Income"), new CategoryName("Sales"), INFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Investments"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Investments"), new CategoryName("Dividends"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Investments"), new CategoryName("Interest"), INFLOW);

        // OUTFLOW categories
        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Housing"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Housing"), new CategoryName("Rent"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Housing"), new CategoryName("Utilities"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Utilities"), new CategoryName("Electricity"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Utilities"), new CategoryName("Gas"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Utilities"), new CategoryName("Internet"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Food"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Food"), new CategoryName("Groceries"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Food"), new CategoryName("Restaurants"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Food"), new CategoryName("Food Delivery"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Transportation"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Transportation"), new CategoryName("Fuel"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Transportation"), new CategoryName("Car Insurance"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Transportation"), new CategoryName("Car Service"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Transportation"), new CategoryName("Public Transit"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Health"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Health"), new CategoryName("Medicine"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Health"), new CategoryName("Doctor Visits"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Health"), new CategoryName("Gym"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Entertainment"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Entertainment"), new CategoryName("Streaming"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Entertainment"), new CategoryName("Cinema"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Entertainment"), new CategoryName("Games"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Entertainment"), new CategoryName("Books"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Education"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Education"), new CategoryName("Courses"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Education"), new CategoryName("Subscriptions"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Clothing"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Clothing"), new CategoryName("Apparel"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Clothing"), new CategoryName("Footwear"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Savings"), OUTFLOW);

        // Set budgeting
        actor.setBudgeting(cashFlowId, new CategoryName("Salary"), INFLOW, Money.of(5000, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Rent"), OUTFLOW, Money.of(1500, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Groceries"), OUTFLOW, Money.of(600, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Fuel"), OUTFLOW, Money.of(300, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Streaming"), OUTFLOW, Money.of(50, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Savings"), OUTFLOW, Money.of(500, "USD"));

        log.info("Home budget categories created with budgeting");
    }

    private void setupBusinessBudgetCategories(CashFlowId cashFlowId) {
        // INFLOW categories
        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Revenue"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Revenue"), new CategoryName("Product Sales"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Revenue"), new CategoryName("Service Revenue"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Revenue"), new CategoryName("Consulting Fees"), INFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Other Revenue"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Other Revenue"), new CategoryName("Interest Income"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Other Revenue"), new CategoryName("Royalties"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Other Revenue"), new CategoryName("Grants"), INFLOW);

        // OUTFLOW categories
        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Operating Expenses"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Operating Expenses"), new CategoryName("Office Rent"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Operating Expenses"), new CategoryName("Office Utilities"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Operating Expenses"), new CategoryName("Office Supplies"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Operating Expenses"), new CategoryName("Equipment"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Payroll"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Payroll"), new CategoryName("Salaries"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Payroll"), new CategoryName("Contractor Payments"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Payroll"), new CategoryName("Benefits"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Marketing"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Marketing"), new CategoryName("Advertising"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Marketing"), new CategoryName("Social Media"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Marketing"), new CategoryName("Events"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Marketing"), new CategoryName("PR"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Technology"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Technology"), new CategoryName("Software Subscriptions"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Technology"), new CategoryName("Cloud Services"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Technology"), new CategoryName("IT Support"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Technology"), new CategoryName("Hardware"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Professional Services"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Professional Services"), new CategoryName("Legal Fees"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Professional Services"), new CategoryName("Accounting"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Professional Services"), new CategoryName("Insurance"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Travel"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Travel"), new CategoryName("Business Trips"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Travel"), new CategoryName("Accommodation"), OUTFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Travel"), new CategoryName("Meals"), OUTFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Taxes"), OUTFLOW);

        // Set budgeting
        actor.setBudgeting(cashFlowId, new CategoryName("Product Sales"), INFLOW, Money.of(25000, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Service Revenue"), INFLOW, Money.of(15000, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Office Rent"), OUTFLOW, Money.of(3000, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Salaries"), OUTFLOW, Money.of(20000, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Advertising"), OUTFLOW, Money.of(2000, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Software Subscriptions"), OUTFLOW, Money.of(1500, "USD"));
        actor.setBudgeting(cashFlowId, new CategoryName("Cloud Services"), OUTFLOW, Money.of(1000, "USD"));

        log.info("Business budget categories created with budgeting");
    }

    private record TransactionResult(CashChangeId lastCashChangeId, PaymentStatus lastPaymentStatus) {}

    private TransactionResult generateHomeBudgetTransactions(CashFlowId cashFlowId, YearMonth currentPeriod, boolean isAttestedMonth, Random random) {
        CashChangeId lastCashChangeId = null;
        PaymentStatus lastPaymentStatus = PaymentStatus.EXPECTED;

        // Check if this is the active month (first day of the month = clock date)
        YearMonth activePeriod = YearMonth.now(clock);
        boolean isActiveMonth = currentPeriod.equals(activePeriod);

        // Salary
        if (random.nextDouble() < 0.95) {
            ZonedDateTime salaryDate = currentPeriod.atDay(10).atStartOfDay(ZoneOffset.UTC);
            CashChangeId salaryId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Salary"), INFLOW,
                    Money.of(4500 + random.nextInt(1000), "USD"), salaryDate, salaryDate.plusDays(1));
            if (isAttestedMonth) {
                actor.confirmCashChange(cashFlowId, salaryId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = salaryId;
        }

        // Rent
        ZonedDateTime rentDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
        CashChangeId rentId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Rent"), OUTFLOW,
                Money.of(1200 + random.nextInt(300), "USD"), rentDate, rentDate.plusDays(5));
        if (isAttestedMonth) {
            actor.confirmCashChange(cashFlowId, rentId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = rentId;

        // Groceries
        int groceryTransactions = 4 + random.nextInt(8);
        for (int i = 0; i < groceryTransactions; i++) {
            ZonedDateTime groceryDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId groceryId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Groceries"), OUTFLOW,
                    Money.of(30 + random.nextInt(150), "USD"), groceryDate, groceryDate);
            if (isAttestedMonth) {
                actor.confirmCashChange(cashFlowId, groceryId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = groceryId;
        }

        // Fuel
        int fuelTransactions = 2 + random.nextInt(4);
        for (int i = 0; i < fuelTransactions; i++) {
            ZonedDateTime fuelDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId fuelId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Fuel"), OUTFLOW,
                    Money.of(40 + random.nextInt(60), "USD"), fuelDate, fuelDate);
            if (isAttestedMonth) {
                actor.confirmCashChange(cashFlowId, fuelId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = fuelId;
        }

        // Streaming
        if (random.nextDouble() < 0.8) {
            ZonedDateTime streamDate = currentPeriod.atDay(1 + random.nextInt(10)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId streamId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Streaming"), OUTFLOW,
                    Money.of(10 + random.nextInt(30), "USD"), streamDate, streamDate.plusDays(3));
            if (isAttestedMonth) {
                actor.confirmCashChange(cashFlowId, streamId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = streamId;
        }

        // Paid transactions - only for active month (paidDate must be <= now)
        // For active month, use first day of month (clock date = 2022-01-01)
        // For future months, skip paid transactions entirely
        if (isActiveMonth) {
            int foodDeliveryCount = 2 + random.nextInt(5);
            for (int i = 0; i < foodDeliveryCount; i++) {
                ZonedDateTime deliveryDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
                CashChangeId deliveryId = actor.appendPaidCashChange(cashFlowId, new CategoryName("Food Delivery"), OUTFLOW,
                        Money.of(15 + random.nextInt(50), "USD"), deliveryDate, deliveryDate, deliveryDate);
                lastCashChangeId = deliveryId;
                lastPaymentStatus = PaymentStatus.PAID;
            }
        }

        return new TransactionResult(lastCashChangeId, lastPaymentStatus);
    }

    private TransactionResult generateBusinessBudgetTransactions(CashFlowId cashFlowId, YearMonth currentPeriod, boolean isAttestedMonth, Random random) {
        CashChangeId lastCashChangeId = null;
        PaymentStatus lastPaymentStatus = PaymentStatus.EXPECTED;

        // Check if this is the active month (first day of the month = clock date)
        YearMonth activePeriod = YearMonth.now(clock);
        boolean isActiveMonth = currentPeriod.equals(activePeriod);

        // Product Sales
        int productSalesCount = 3 + random.nextInt(10);
        for (int i = 0; i < productSalesCount; i++) {
            ZonedDateTime saleDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId saleId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Product Sales"), INFLOW,
                    Money.of(500 + random.nextInt(5000), "USD"), saleDate, saleDate.plusDays(random.nextInt(30)));
            if (isAttestedMonth) {
                actor.confirmCashChange(cashFlowId, saleId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = saleId;
        }

        // Service Revenue
        int serviceRevenueCount = 1 + random.nextInt(5);
        for (int i = 0; i < serviceRevenueCount; i++) {
            ZonedDateTime serviceDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId serviceId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Service Revenue"), INFLOW,
                    Money.of(1000 + random.nextInt(10000), "USD"), serviceDate, serviceDate.plusDays(30));
            if (isAttestedMonth) {
                actor.confirmCashChange(cashFlowId, serviceId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = serviceId;
        }

        // Office Rent
        ZonedDateTime rentDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
        CashChangeId rentId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Office Rent"), OUTFLOW,
                Money.of(2500 + random.nextInt(1000), "USD"), rentDate, rentDate.plusDays(5));
        if (isAttestedMonth) {
            actor.confirmCashChange(cashFlowId, rentId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = rentId;

        // Salaries
        ZonedDateTime salaryDate = currentPeriod.atDay(25).atStartOfDay(ZoneOffset.UTC);
        CashChangeId salaryId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Salaries"), OUTFLOW,
                Money.of(15000 + random.nextInt(10000), "USD"), salaryDate, salaryDate.plusDays(5));
        if (isAttestedMonth) {
            actor.confirmCashChange(cashFlowId, salaryId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = salaryId;

        // Software Subscriptions
        ZonedDateTime softwareDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
        CashChangeId softwareId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Software Subscriptions"), OUTFLOW,
                Money.of(500 + random.nextInt(1500), "USD"), softwareDate, softwareDate.plusDays(3));
        if (isAttestedMonth) {
            actor.confirmCashChange(cashFlowId, softwareId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = softwareId;

        // Cloud Services
        ZonedDateTime cloudDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
        CashChangeId cloudId = actor.appendExpectedCashChange(cashFlowId, new CategoryName("Cloud Services"), OUTFLOW,
                Money.of(300 + random.nextInt(2000), "USD"), cloudDate, cloudDate.plusDays(5));
        if (isAttestedMonth) {
            actor.confirmCashChange(cashFlowId, cloudId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = cloudId;

        // Paid transactions - only for active month (paidDate must be <= now)
        // For active month, use first day of month (clock date = 2022-01-01)
        // For future months, skip paid transactions entirely
        if (isActiveMonth) {
            int mealsCount = 3 + random.nextInt(8);
            for (int i = 0; i < mealsCount; i++) {
                ZonedDateTime mealDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
                CashChangeId mealId = actor.appendPaidCashChange(cashFlowId, new CategoryName("Meals"), OUTFLOW,
                        Money.of(15 + random.nextInt(80), "USD"), mealDate, mealDate, mealDate);
                lastCashChangeId = mealId;
                lastPaymentStatus = PaymentStatus.PAID;
            }
        }

        return new TransactionResult(lastCashChangeId, lastPaymentStatus);
    }

    protected boolean cashChangeInStatusHasBeenProcessed(CashFlowId cashFlowId, YearMonth period, CashChangeId cashChangeId, PaymentStatus paymentStatus) {
        return statementRepository.findByCashFlowId(cashFlowId)
                .map(CashFlowForecastStatement::getForecasts)
                .stream().map(yearMonthCashFlowMonthlyForecastMap -> yearMonthCashFlowMonthlyForecastMap.get(period))
                .filter(Objects::nonNull)
                .anyMatch(cashFlowMonthlyForecast -> {
                    List<CashCategory> allCategories = flattenCategories(Stream.concat(
                            Optional.ofNullable(cashFlowMonthlyForecast.getCategorizedInFlows()).orElse(List.of()).stream(),
                            Optional.ofNullable(cashFlowMonthlyForecast.getCategorizedOutFlows()).orElse(List.of()).stream()
                    ).toList());

                    return allCategories.stream()
                            .map(cashCategory -> cashCategory.getGroupedTransactions().fetchTransaction(cashChangeId))
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .anyMatch(transaction -> paymentStatus.equals(transaction.paymentStatus()));
                });
    }

    private List<CashCategory> flattenCategories(List<CashCategory> cashCategories) {
        Stack<CashCategory> stack = new Stack<>();
        List<CashCategory> outcome = new LinkedList<>();
        cashCategories.forEach(stack::push);
        while (!stack.isEmpty()) {
            CashCategory takenCashCategory = stack.pop();
            outcome.add(takenCashCategory);
            takenCashCategory.getSubCategories().forEach(stack::push);
        }
        return outcome;
    }
}
