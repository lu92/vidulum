package com.multi.vidulum;

import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.app.CashFlowRestController;
import com.multi.vidulum.cashflow.app.commands.append.AppendCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.attest.MakeMonthlyAttestationCommand;
import com.multi.vidulum.cashflow.app.commands.comment.create.CreateCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.edit.EditCashChangeCommand;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow_forecast_processor.app.CashCategory;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.trading.domain.IntegrationTest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
public class DualCashflowStatementGenerator extends IntegrationTest {

    @Autowired
    private DualBudgetActor actor;

    @Autowired
    private CashFlowRestController cashFlowRestController;

    @Autowired
    private Clock clock;

    private static final int MIN_MONTHS = 30;
    private static final int MAX_MONTHS = 36;
    private static final int ATTESTED_MONTHS_OFFSET = 6;
    private static final String USER_ID = "dual-budget-user";

    @Test
    public void generateDualCashflows() {
        Random random = new Random();
        int numberOfMonths = MIN_MONTHS + random.nextInt(MAX_MONTHS - MIN_MONTHS + 1);
        int numberOfAttestedMonths = numberOfMonths - ATTESTED_MONTHS_OFFSET;

        log.info("Generating dual cashflows for {} months, {} attested", numberOfMonths, numberOfAttestedMonths);

        // Create Home Budget CashFlow
        CashFlowId homeCashFlowId = actor.createHomeBudgetCashFlow(USER_ID);
        log.info("Created home budget cashflow: {}", homeCashFlowId);

        // Create Business Budget CashFlow
        CashFlowId businessCashFlowId = actor.createBusinessBudgetCashFlow(USER_ID);
        log.info("Created business budget cashflow: {}", businessCashFlowId);

        // Setup Home Budget Categories
        setupHomeBudgetCategories(homeCashFlowId);

        // Setup Business Budget Categories
        setupBusinessBudgetCategories(businessCashFlowId);

        // Track last cash change for verification
        CashChangeId homeLastCashChangeId = null;
        PaymentStatus homeLastPaymentStatus = PaymentStatus.EXPECTED;
        YearMonth homeLastPeriod = YearMonth.now(clock);

        CashChangeId businessLastCashChangeId = null;
        PaymentStatus businessLastPaymentStatus = PaymentStatus.EXPECTED;
        YearMonth businessLastPeriod = YearMonth.now(clock);

        // Generate transactions for each month
        for (int monthOffset = 0; monthOffset < numberOfMonths; monthOffset++) {
            YearMonth currentPeriod = YearMonth.now(clock).plusMonths(monthOffset);
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
            }
        }

        // Wait for home cashflow to be processed
        await().until(() -> statementRepository.findByCashFlowId(homeCashFlowId).isPresent());
        CashChangeId finalHomeLastCashChangeId = homeLastCashChangeId;
        PaymentStatus finalHomeLastPaymentStatus = homeLastPaymentStatus;
        YearMonth finalHomeLastPeriod = homeLastPeriod;
        await().atMost(30, SECONDS).until(() -> cashChangeInStatusHasBeenProcessed(homeCashFlowId, finalHomeLastPeriod, finalHomeLastCashChangeId, finalHomeLastPaymentStatus));

        // Wait for business cashflow to be processed
        await().until(() -> statementRepository.findByCashFlowId(businessCashFlowId).isPresent());
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
                    assertThat(detail.getName()).isEqualTo("Home Budget");
                });
        assertThat(userCashFlows)
                .anySatisfy(detail -> {
                    assertThat(detail.getCashFlowId()).isEqualTo(businessCashFlowId.id());
                    assertThat(detail.getUserId()).isEqualTo(USER_ID);
                    assertThat(detail.getName()).isEqualTo("Business Budget");
                });

        log.info("Successfully verified 2 cashflows for user {} via /viaUser endpoint", USER_ID);
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

        // Set budgeting for selected categories (categories WITH budgeting that have transactions)
        // Salary - expected monthly income
        actor.setBudgeting(cashFlowId, new CategoryName("Salary"), INFLOW, Money.of(5000, "USD"));

        // Rent - fixed monthly expense with budget
        actor.setBudgeting(cashFlowId, new CategoryName("Rent"), OUTFLOW, Money.of(1500, "USD"));

        // Groceries - monthly budget for food shopping
        actor.setBudgeting(cashFlowId, new CategoryName("Groceries"), OUTFLOW, Money.of(600, "USD"));

        // Fuel - monthly transportation budget
        actor.setBudgeting(cashFlowId, new CategoryName("Fuel"), OUTFLOW, Money.of(300, "USD"));

        // Streaming - entertainment budget
        actor.setBudgeting(cashFlowId, new CategoryName("Streaming"), OUTFLOW, Money.of(50, "USD"));

        // Savings - monthly savings goal
        actor.setBudgeting(cashFlowId, new CategoryName("Savings"), OUTFLOW, Money.of(500, "USD"));

        // Note: Categories WITHOUT budgeting that still have transactions:
        // - Bonus (INFLOW) - no budget, occasional income
        // - Dividends, Interest (INFLOW) - no budget, variable income
        // - Restaurants (OUTFLOW) - no budget, discretionary spending
        // - Gym (OUTFLOW) - no budget
        // - Electricity, Gas, Internet (OUTFLOW) - no budget, variable utilities

        log.info("Home budget categories created with budgeting set for: Salary, Rent, Groceries, Fuel, Streaming, Savings");
    }

    private void setupBusinessBudgetCategories(CashFlowId cashFlowId) {
        // INFLOW categories - Revenue
        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Revenue"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Revenue"), new CategoryName("Product Sales"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Revenue"), new CategoryName("Service Revenue"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Revenue"), new CategoryName("Consulting Fees"), INFLOW);

        actor.addCategory(cashFlowId, CategoryName.NOT_DEFINED, new CategoryName("Other Revenue"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Other Revenue"), new CategoryName("Interest Income"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Other Revenue"), new CategoryName("Royalties"), INFLOW);
        actor.addCategory(cashFlowId, new CategoryName("Other Revenue"), new CategoryName("Grants"), INFLOW);

        // OUTFLOW categories - Operating Expenses
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

        // Set budgeting for selected categories (categories WITH budgeting that have transactions)
        // Product Sales - expected monthly revenue target
        actor.setBudgeting(cashFlowId, new CategoryName("Product Sales"), INFLOW, Money.of(25000, "USD"));

        // Service Revenue - expected service income
        actor.setBudgeting(cashFlowId, new CategoryName("Service Revenue"), INFLOW, Money.of(15000, "USD"));

        // Office Rent - fixed monthly expense
        actor.setBudgeting(cashFlowId, new CategoryName("Office Rent"), OUTFLOW, Money.of(3000, "USD"));

        // Salaries - fixed payroll budget
        actor.setBudgeting(cashFlowId, new CategoryName("Salaries"), OUTFLOW, Money.of(20000, "USD"));

        // Advertising - marketing budget
        actor.setBudgeting(cashFlowId, new CategoryName("Advertising"), OUTFLOW, Money.of(2000, "USD"));

        // Software Subscriptions - technology budget
        actor.setBudgeting(cashFlowId, new CategoryName("Software Subscriptions"), OUTFLOW, Money.of(1500, "USD"));

        // Cloud Services - infrastructure budget
        actor.setBudgeting(cashFlowId, new CategoryName("Cloud Services"), OUTFLOW, Money.of(1000, "USD"));

        // Note: Categories WITHOUT budgeting that still have transactions:
        // - Consulting Fees (INFLOW) - no budget, variable income
        // - Interest Income (INFLOW) - no budget, variable income
        // - Office Utilities (OUTFLOW) - no budget, variable expense
        // - Contractor Payments (OUTFLOW) - no budget, variable expense
        // - Social Media (OUTFLOW) - no budget
        // - Legal Fees (OUTFLOW) - no budget, occasional expense
        // - Accounting (OUTFLOW) - no budget
        // - Business Trips (OUTFLOW) - no budget, occasional expense
        // - Taxes (OUTFLOW) - no budget, quarterly

        log.info("Business budget categories created with budgeting set for: Product Sales, Service Revenue, Office Rent, Salaries, Advertising, Software Subscriptions, Cloud Services");
    }

    private record TransactionResult(CashChangeId lastCashChangeId, PaymentStatus lastPaymentStatus) {}

    private TransactionResult generateHomeBudgetTransactions(CashFlowId cashFlowId, YearMonth currentPeriod, boolean isAttestedMonth, Random random) {
        CashChangeId lastCashChangeId = null;
        PaymentStatus lastPaymentStatus = PaymentStatus.EXPECTED;

        // Salary - almost always present
        if (random.nextDouble() < 0.95) {
            ZonedDateTime salaryDate = currentPeriod.atDay(10).atStartOfDay(ZoneOffset.UTC);
            CashChangeId salaryId = actor.appendCashChange(cashFlowId, new CategoryName("Salary"), INFLOW,
                    Money.of(4500 + random.nextInt(1000), "USD"), salaryDate, salaryDate.plusDays(1));
            if (isAttestedMonth || random.nextDouble() < 0.8) {
                actor.confirmCashChange(cashFlowId, salaryId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = salaryId;
        }

        // Bonus (occasional)
        if (random.nextDouble() < 0.15) {
            ZonedDateTime bonusDate = currentPeriod.atDay(15 + random.nextInt(10)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId bonusId = actor.appendCashChange(cashFlowId, new CategoryName("Bonus"), INFLOW,
                    Money.of(500 + random.nextInt(2500), "USD"), bonusDate, bonusDate.plusDays(1));
            if (isAttestedMonth && random.nextDouble() < 0.9) {
                actor.confirmCashChange(cashFlowId, bonusId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = bonusId;
        }

        // Dividends/Interest
        if (random.nextDouble() < 0.25) {
            CategoryName investmentCategory = random.nextBoolean() ? new CategoryName("Dividends") : new CategoryName("Interest");
            ZonedDateTime investDate = currentPeriod.atDay(1 + random.nextInt(25)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId investId = actor.appendCashChange(cashFlowId, investmentCategory, INFLOW,
                    Money.of(25 + random.nextInt(250), "USD"), investDate, investDate.plusDays(3));
            if (isAttestedMonth && random.nextDouble() < 0.85) {
                actor.confirmCashChange(cashFlowId, investId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = investId;
        }

        // Rent - always present
        ZonedDateTime rentDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
        CashChangeId rentId = actor.appendCashChange(cashFlowId, new CategoryName("Rent"), OUTFLOW,
                Money.of(1200 + random.nextInt(300), "USD"), rentDate, rentDate.plusDays(5));
        if (isAttestedMonth) {
            actor.confirmCashChange(cashFlowId, rentId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = rentId;

        // Utilities
        for (CategoryName utility : List.of(new CategoryName("Electricity"), new CategoryName("Gas"), new CategoryName("Internet"))) {
            ZonedDateTime utilityDate = currentPeriod.atDay(5 + random.nextInt(10)).atStartOfDay(ZoneOffset.UTC);
            int amount = utility.name().equals("Internet") ? 50 + random.nextInt(30) : 60 + random.nextInt(100);
            CashChangeId utilityId = actor.appendCashChange(cashFlowId, utility, OUTFLOW,
                    Money.of(amount, "USD"), utilityDate, utilityDate.plusDays(10));
            if (isAttestedMonth && random.nextDouble() < 0.95) {
                actor.confirmCashChange(cashFlowId, utilityId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = utilityId;
        }

        // Groceries - multiple transactions
        int groceryTransactions = 4 + random.nextInt(8);
        for (int i = 0; i < groceryTransactions; i++) {
            ZonedDateTime groceryDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId groceryId = actor.appendCashChange(cashFlowId, new CategoryName("Groceries"), OUTFLOW,
                    Money.of(30 + random.nextInt(150), "USD"), groceryDate, groceryDate);
            if (isAttestedMonth && random.nextDouble() < 0.9) {
                actor.confirmCashChange(cashFlowId, groceryId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = groceryId;
        }

        // Restaurants
        int restaurantVisits = random.nextInt(6);
        for (int i = 0; i < restaurantVisits; i++) {
            ZonedDateTime restDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId restId = actor.appendCashChange(cashFlowId, new CategoryName("Restaurants"), OUTFLOW,
                    Money.of(20 + random.nextInt(100), "USD"), restDate, restDate);
            if (isAttestedMonth && random.nextDouble() < 0.85) {
                actor.confirmCashChange(cashFlowId, restId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = restId;
        }

        // Fuel
        int fuelTransactions = 2 + random.nextInt(4);
        for (int i = 0; i < fuelTransactions; i++) {
            ZonedDateTime fuelDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId fuelId = actor.appendCashChange(cashFlowId, new CategoryName("Fuel"), OUTFLOW,
                    Money.of(40 + random.nextInt(60), "USD"), fuelDate, fuelDate);
            if (isAttestedMonth && random.nextDouble() < 0.9) {
                actor.confirmCashChange(cashFlowId, fuelId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = fuelId;
        }

        // Gym (monthly)
        if (random.nextDouble() < 0.7) {
            ZonedDateTime gymDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
            CashChangeId gymId = actor.appendCashChange(cashFlowId, new CategoryName("Gym"), OUTFLOW,
                    Money.of(30 + random.nextInt(50), "USD"), gymDate, gymDate.plusDays(5));
            if (isAttestedMonth && random.nextDouble() < 0.95) {
                actor.confirmCashChange(cashFlowId, gymId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = gymId;
        }

        // Streaming services
        for (String service : List.of("Netflix", "Spotify", "HBO")) {
            if (random.nextDouble() < 0.6) {
                ZonedDateTime streamDate = currentPeriod.atDay(1 + random.nextInt(10)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId streamId = actor.appendCashChange(cashFlowId, new CategoryName("Streaming"), OUTFLOW,
                        Money.of(10 + random.nextInt(20), "USD"), streamDate, streamDate.plusDays(3));
                if (isAttestedMonth && random.nextDouble() < 0.95) {
                    actor.confirmCashChange(cashFlowId, streamId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = streamId;
            }
        }

        // Savings (monthly)
        if (random.nextDouble() < 0.6) {
            ZonedDateTime savingsDate = currentPeriod.atDay(25 + random.nextInt(3)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId savingsId = actor.appendCashChange(cashFlowId, new CategoryName("Savings"), OUTFLOW,
                    Money.of(200 + random.nextInt(800), "USD"), savingsDate, savingsDate.plusDays(3));
            if (isAttestedMonth && random.nextDouble() < 0.9) {
                actor.confirmCashChange(cashFlowId, savingsId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = savingsId;
        }

        return new TransactionResult(lastCashChangeId, lastPaymentStatus);
    }

    private TransactionResult generateBusinessBudgetTransactions(CashFlowId cashFlowId, YearMonth currentPeriod, boolean isAttestedMonth, Random random) {
        CashChangeId lastCashChangeId = null;
        PaymentStatus lastPaymentStatus = PaymentStatus.EXPECTED;

        // Product Sales - multiple per month
        int productSalesCount = 3 + random.nextInt(10);
        for (int i = 0; i < productSalesCount; i++) {
            ZonedDateTime saleDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId saleId = actor.appendCashChange(cashFlowId, new CategoryName("Product Sales"), INFLOW,
                    Money.of(500 + random.nextInt(5000), "USD"), saleDate, saleDate.plusDays(random.nextInt(30)));
            if (isAttestedMonth && random.nextDouble() < 0.85) {
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
            CashChangeId serviceId = actor.appendCashChange(cashFlowId, new CategoryName("Service Revenue"), INFLOW,
                    Money.of(1000 + random.nextInt(10000), "USD"), serviceDate, serviceDate.plusDays(30));
            if (isAttestedMonth && random.nextDouble() < 0.8) {
                actor.confirmCashChange(cashFlowId, serviceId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = serviceId;
        }

        // Consulting Fees (occasional)
        if (random.nextDouble() < 0.4) {
            ZonedDateTime consultDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId consultId = actor.appendCashChange(cashFlowId, new CategoryName("Consulting Fees"), INFLOW,
                    Money.of(2000 + random.nextInt(8000), "USD"), consultDate, consultDate.plusDays(14));
            if (isAttestedMonth && random.nextDouble() < 0.85) {
                actor.confirmCashChange(cashFlowId, consultId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = consultId;
        }

        // Interest Income (occasional)
        if (random.nextDouble() < 0.3) {
            ZonedDateTime interestDate = currentPeriod.atDay(28).atStartOfDay(ZoneOffset.UTC);
            CashChangeId interestId = actor.appendCashChange(cashFlowId, new CategoryName("Interest Income"), INFLOW,
                    Money.of(50 + random.nextInt(500), "USD"), interestDate, interestDate.plusDays(3));
            if (isAttestedMonth && random.nextDouble() < 0.9) {
                actor.confirmCashChange(cashFlowId, interestId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = interestId;
        }

        // Office Rent - always present
        ZonedDateTime rentDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
        CashChangeId rentId = actor.appendCashChange(cashFlowId, new CategoryName("Office Rent"), OUTFLOW,
                Money.of(2500 + random.nextInt(1000), "USD"), rentDate, rentDate.plusDays(5));
        if (isAttestedMonth) {
            actor.confirmCashChange(cashFlowId, rentId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = rentId;

        // Office Utilities
        ZonedDateTime utilityDate = currentPeriod.atDay(5 + random.nextInt(5)).atStartOfDay(ZoneOffset.UTC);
        CashChangeId utilityId = actor.appendCashChange(cashFlowId, new CategoryName("Office Utilities"), OUTFLOW,
                Money.of(200 + random.nextInt(300), "USD"), utilityDate, utilityDate.plusDays(10));
        if (isAttestedMonth && random.nextDouble() < 0.95) {
            actor.confirmCashChange(cashFlowId, utilityId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = utilityId;

        // Office Supplies (occasional)
        if (random.nextDouble() < 0.5) {
            ZonedDateTime suppliesDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId suppliesId = actor.appendCashChange(cashFlowId, new CategoryName("Office Supplies"), OUTFLOW,
                    Money.of(50 + random.nextInt(300), "USD"), suppliesDate, suppliesDate.plusDays(7));
            if (isAttestedMonth && random.nextDouble() < 0.9) {
                actor.confirmCashChange(cashFlowId, suppliesId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = suppliesId;
        }

        // Salaries - always present
        ZonedDateTime salaryDate = currentPeriod.atDay(25).atStartOfDay(ZoneOffset.UTC);
        CashChangeId salaryId = actor.appendCashChange(cashFlowId, new CategoryName("Salaries"), OUTFLOW,
                Money.of(15000 + random.nextInt(10000), "USD"), salaryDate, salaryDate.plusDays(5));
        if (isAttestedMonth) {
            actor.confirmCashChange(cashFlowId, salaryId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = salaryId;

        // Contractor Payments
        int contractorPayments = random.nextInt(5);
        for (int i = 0; i < contractorPayments; i++) {
            ZonedDateTime contractorDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId contractorId = actor.appendCashChange(cashFlowId, new CategoryName("Contractor Payments"), OUTFLOW,
                    Money.of(500 + random.nextInt(3000), "USD"), contractorDate, contractorDate.plusDays(14));
            if (isAttestedMonth && random.nextDouble() < 0.85) {
                actor.confirmCashChange(cashFlowId, contractorId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = contractorId;
        }

        // Marketing - Advertising
        if (random.nextDouble() < 0.7) {
            ZonedDateTime adDate = currentPeriod.atDay(1 + random.nextInt(15)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId adId = actor.appendCashChange(cashFlowId, new CategoryName("Advertising"), OUTFLOW,
                    Money.of(500 + random.nextInt(3000), "USD"), adDate, adDate.plusDays(7));
            if (isAttestedMonth && random.nextDouble() < 0.9) {
                actor.confirmCashChange(cashFlowId, adId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = adId;
        }

        // Social Media Marketing
        if (random.nextDouble() < 0.6) {
            ZonedDateTime socialDate = currentPeriod.atDay(1 + random.nextInt(10)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId socialId = actor.appendCashChange(cashFlowId, new CategoryName("Social Media"), OUTFLOW,
                    Money.of(200 + random.nextInt(800), "USD"), socialDate, socialDate.plusDays(5));
            if (isAttestedMonth && random.nextDouble() < 0.9) {
                actor.confirmCashChange(cashFlowId, socialId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = socialId;
        }

        // Software Subscriptions - always present
        ZonedDateTime softwareDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
        CashChangeId softwareId = actor.appendCashChange(cashFlowId, new CategoryName("Software Subscriptions"), OUTFLOW,
                Money.of(500 + random.nextInt(1500), "USD"), softwareDate, softwareDate.plusDays(3));
        if (isAttestedMonth && random.nextDouble() < 0.95) {
            actor.confirmCashChange(cashFlowId, softwareId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = softwareId;

        // Cloud Services
        ZonedDateTime cloudDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
        CashChangeId cloudId = actor.appendCashChange(cashFlowId, new CategoryName("Cloud Services"), OUTFLOW,
                Money.of(300 + random.nextInt(2000), "USD"), cloudDate, cloudDate.plusDays(5));
        if (isAttestedMonth && random.nextDouble() < 0.95) {
            actor.confirmCashChange(cashFlowId, cloudId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = cloudId;

        // Legal Fees (occasional)
        if (random.nextDouble() < 0.2) {
            ZonedDateTime legalDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId legalId = actor.appendCashChange(cashFlowId, new CategoryName("Legal Fees"), OUTFLOW,
                    Money.of(500 + random.nextInt(5000), "USD"), legalDate, legalDate.plusDays(30));
            if (isAttestedMonth && random.nextDouble() < 0.85) {
                actor.confirmCashChange(cashFlowId, legalId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = legalId;
        }

        // Accounting (monthly)
        ZonedDateTime accountingDate = currentPeriod.atDay(28).atStartOfDay(ZoneOffset.UTC);
        CashChangeId accountingId = actor.appendCashChange(cashFlowId, new CategoryName("Accounting"), OUTFLOW,
                Money.of(300 + random.nextInt(500), "USD"), accountingDate, accountingDate.plusDays(7));
        if (isAttestedMonth && random.nextDouble() < 0.95) {
            actor.confirmCashChange(cashFlowId, accountingId);
            lastPaymentStatus = PaymentStatus.PAID;
        } else {
            lastPaymentStatus = PaymentStatus.EXPECTED;
        }
        lastCashChangeId = accountingId;

        // Insurance (quarterly)
        if (currentPeriod.getMonthValue() % 3 == 1) {
            ZonedDateTime insuranceDate = currentPeriod.atDay(15).atStartOfDay(ZoneOffset.UTC);
            CashChangeId insuranceId = actor.appendCashChange(cashFlowId, new CategoryName("Insurance"), OUTFLOW,
                    Money.of(1000 + random.nextInt(2000), "USD"), insuranceDate, insuranceDate.plusDays(14));
            if (isAttestedMonth) {
                actor.confirmCashChange(cashFlowId, insuranceId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = insuranceId;
        }

        // Business Trips (occasional)
        if (random.nextDouble() < 0.25) {
            ZonedDateTime tripDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
            CashChangeId tripId = actor.appendCashChange(cashFlowId, new CategoryName("Business Trips"), OUTFLOW,
                    Money.of(500 + random.nextInt(2000), "USD"), tripDate, tripDate.plusDays(14));
            if (isAttestedMonth && random.nextDouble() < 0.85) {
                actor.confirmCashChange(cashFlowId, tripId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = tripId;
        }

        // Taxes (quarterly)
        if (currentPeriod.getMonthValue() % 3 == 0) {
            ZonedDateTime taxDate = currentPeriod.atDay(15).atStartOfDay(ZoneOffset.UTC);
            CashChangeId taxId = actor.appendCashChange(cashFlowId, new CategoryName("Taxes"), OUTFLOW,
                    Money.of(5000 + random.nextInt(15000), "USD"), taxDate, taxDate.plusDays(14));
            if (isAttestedMonth) {
                actor.confirmCashChange(cashFlowId, taxId);
                lastPaymentStatus = PaymentStatus.PAID;
            } else {
                lastPaymentStatus = PaymentStatus.EXPECTED;
            }
            lastCashChangeId = taxId;
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

@Component
@AllArgsConstructor
class DualBudgetActor {

    private CashFlowRestController cashFlowRestController;
    private CommandGateway commandGateway;

    CashFlowId createHomeBudgetCashFlow(String userId) {
        return new CashFlowId(cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId(userId)
                        .name("Home Budget")
                        .description("Personal home budget with multiple categories")
                        .bankAccount(new BankAccount(
                                new BankName("Chase Bank"),
                                new BankAccountNumber("US12345678901234567890", Currency.of("USD")),
                                Money.of(10000, "USD")))
                        .build()
        ));
    }

    CashFlowId createBusinessBudgetCashFlow(String userId) {
        return new CashFlowId(cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId(userId)
                        .name("Business Budget")
                        .description("Business budget with revenue and expenses tracking")
                        .bankAccount(new BankAccount(
                                new BankName("Bank of America"),
                                new BankAccountNumber("US98765432109876543210", Currency.of("USD")),
                                Money.of(50000, "USD")))
                        .build()
        ));
    }

    CashChangeId appendCashChange(CashFlowId cashFlowId, CategoryName category, Type type, Money money, ZonedDateTime created, ZonedDateTime dueDate) {
        return commandGateway.send(
                new AppendCashChangeCommand(
                        cashFlowId,
                        category,
                        new CashChangeId(CashChangeId.generate().id()),
                        new Name("Transaction: " + category.name()),
                        new Description("Auto-generated transaction in category " + category.name()),
                        money,
                        type,
                        created,
                        dueDate
                ));
    }

    void confirmCashChange(CashFlowId cashFlowId, CashChangeId cashChangeId) {
        cashFlowRestController.confirm(
                CashFlowDto.ConfirmCashChangeJson.builder()
                        .cashFlowId(cashFlowId.id())
                        .cashChangeId(cashChangeId.id())
                        .build()
        );
    }

    void editCashChange(CashFlowId cashFlowId, CashChangeId cashChangeId, Name name, Description description, Money money, ZonedDateTime dueDate) {
        commandGateway.send(new EditCashChangeCommand(
                cashFlowId,
                cashChangeId,
                name,
                description,
                money,
                dueDate
        ));
    }

    void attestMonth(CashFlowId cashFlowId, YearMonth period, Money currentMoney, ZonedDateTime dateTime) {
        commandGateway.send(new MakeMonthlyAttestationCommand(
                cashFlowId, period, currentMoney, dateTime
        ));
    }

    void addCategory(CashFlowId cashFlowId, CategoryName parentCategoryName, CategoryName categoryName, Type type) {
        commandGateway.send(new CreateCategoryCommand(
                cashFlowId,
                parentCategoryName,
                categoryName,
                type
        ));
    }

    void setBudgeting(CashFlowId cashFlowId, CategoryName categoryName, Type categoryType, Money budget) {
        cashFlowRestController.setBudgeting(
                CashFlowDto.SetBudgetingJson.builder()
                        .cashFlowId(cashFlowId.id())
                        .categoryName(categoryName.name())
                        .categoryType(categoryType)
                        .budget(budget)
                        .build()
        );
    }
}
