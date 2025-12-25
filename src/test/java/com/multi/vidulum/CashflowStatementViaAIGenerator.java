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
import static org.awaitility.Awaitility.await;

@Slf4j
public class CashflowStatementViaAIGenerator extends IntegrationTest {

    @Autowired
    private HomeBudgetActor actor;

    @Autowired
    private Clock clock;

    private static final int MIN_MONTHS = 30;
    private static final int MAX_MONTHS = 36;
    private static final int ATTESTED_MONTHS_OFFSET = 6;

    @Test
    public void generate() {
        Random random = new Random();
        int numberOfMonths = MIN_MONTHS + random.nextInt(MAX_MONTHS - MIN_MONTHS + 1);
        int numberOfAttestedMonths = numberOfMonths - ATTESTED_MONTHS_OFFSET;

        log.info("Generating cashflow for {} months, {} attested", numberOfMonths, numberOfAttestedMonths);

        CashFlowId cashFlowId = actor.createCashFlow();
        log.info("Created cashflow: {}", cashFlowId);

        // Create home budget categories - INFLOW
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

        // Create home budget categories - OUTFLOW
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

        // Track last cash change for verification
        CashChangeId lastCashChangeId = null;
        PaymentStatus lastPaymentStatus = PaymentStatus.EXPECTED;
        YearMonth lastPeriod = YearMonth.now(clock);

        // Generate transactions for each month
        for (int monthOffset = 0; monthOffset < numberOfMonths; monthOffset++) {
            YearMonth currentPeriod = YearMonth.now(clock).plusMonths(monthOffset);
            boolean isAttestedMonth = monthOffset < numberOfAttestedMonths;

            log.info("Processing month: {} (attested: {})", currentPeriod, isAttestedMonth);

            // Generate regular monthly income (salary) - almost always present
            if (random.nextDouble() < 0.95) {
                ZonedDateTime salaryDate = currentPeriod.atDay(10).atStartOfDay(ZoneOffset.UTC);
                CashChangeId salaryId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Salary"),
                        INFLOW,
                        Money.of(4500 + random.nextInt(1000), "USD"),
                        salaryDate,
                        salaryDate.plusDays(1)
                );

                // Salary is usually confirmed
                if (isAttestedMonth || random.nextDouble() < 0.8) {
                    actor.confirmCashChange(cashFlowId, salaryId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = salaryId;
            }

            // Generate bonus (occasional)
            if (random.nextDouble() < 0.15) {
                ZonedDateTime bonusDate = currentPeriod.atDay(15 + random.nextInt(10)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId bonusId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Bonus"),
                        INFLOW,
                        Money.of(500 + random.nextInt(2500), "USD"),
                        bonusDate,
                        bonusDate.plusDays(1)
                );

                if (isAttestedMonth && random.nextDouble() < 0.9) {
                    actor.confirmCashChange(cashFlowId, bonusId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = bonusId;
            }

            // Generate dividends/interest (occasional)
            if (random.nextDouble() < 0.25) {
                CategoryName investmentCategory = random.nextBoolean() ? new CategoryName("Dividends") : new CategoryName("Interest");
                ZonedDateTime investDate = currentPeriod.atDay(1 + random.nextInt(25)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId investId = actor.appendCashChange(
                        cashFlowId,
                        investmentCategory,
                        INFLOW,
                        Money.of(25 + random.nextInt(250), "USD"),
                        investDate,
                        investDate.plusDays(3)
                );

                if (isAttestedMonth && random.nextDouble() < 0.85) {
                    actor.confirmCashChange(cashFlowId, investId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = investId;
            }

            // Generate other income (sporadic)
            for (CategoryName otherIncome : List.of(new CategoryName("Refunds"), new CategoryName("Gifts"), new CategoryName("Sales"))) {
                if (random.nextDouble() < 0.1) {
                    ZonedDateTime otherDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                    CashChangeId otherId = actor.appendCashChange(
                            cashFlowId,
                            otherIncome,
                            INFLOW,
                            Money.of(25 + random.nextInt(500), "USD"),
                            otherDate,
                            otherDate.plusDays(5)
                    );

                    if (isAttestedMonth && random.nextDouble() < 0.7) {
                        actor.confirmCashChange(cashFlowId, otherId);
                        lastPaymentStatus = PaymentStatus.PAID;
                    } else {
                        lastPaymentStatus = PaymentStatus.EXPECTED;
                    }
                    lastCashChangeId = otherId;
                }
            }

            // Fixed monthly expenses (rent, utilities)
            // Rent - always present
            ZonedDateTime rentDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
            CashChangeId rentId = actor.appendCashChange(
                    cashFlowId,
                    new CategoryName("Rent"),
                    OUTFLOW,
                    Money.of(1200 + random.nextInt(300), "USD"),
                    rentDate,
                    rentDate.plusDays(5)
            );
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
                CashChangeId utilityId = actor.appendCashChange(
                        cashFlowId,
                        utility,
                        OUTFLOW,
                        Money.of(amount, "USD"),
                        utilityDate,
                        utilityDate.plusDays(10)
                );

                if (isAttestedMonth && random.nextDouble() < 0.95) {
                    actor.confirmCashChange(cashFlowId, utilityId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = utilityId;
            }

            // Food - multiple transactions per month
            int groceryTransactions = 4 + random.nextInt(8);
            for (int i = 0; i < groceryTransactions; i++) {
                ZonedDateTime groceryDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId groceryId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Groceries"),
                        OUTFLOW,
                        Money.of(30 + random.nextInt(150), "USD"),
                        groceryDate,
                        groceryDate
                );

                // Sometimes edit amount before confirming
                if (random.nextDouble() < 0.1) {
                    actor.editCashChange(
                            cashFlowId,
                            groceryId,
                            new Name("Groceries - corrected"),
                            new Description("Corrected grocery amount"),
                            Money.of(30 + random.nextInt(180), "USD"),
                            groceryDate.plusDays(1)
                    );
                }

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
                CashChangeId restId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Restaurants"),
                        OUTFLOW,
                        Money.of(20 + random.nextInt(100), "USD"),
                        restDate,
                        restDate
                );

                if (isAttestedMonth && random.nextDouble() < 0.85) {
                    actor.confirmCashChange(cashFlowId, restId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = restId;
            }

            // Food delivery
            int deliveries = random.nextInt(8);
            for (int i = 0; i < deliveries; i++) {
                ZonedDateTime deliveryDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId deliveryId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Food Delivery"),
                        OUTFLOW,
                        Money.of(15 + random.nextInt(50), "USD"),
                        deliveryDate,
                        deliveryDate
                );

                if (isAttestedMonth && random.nextDouble() < 0.85) {
                    actor.confirmCashChange(cashFlowId, deliveryId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = deliveryId;
            }

            // Transport - fuel
            int fuelTransactions = 2 + random.nextInt(4);
            for (int i = 0; i < fuelTransactions; i++) {
                ZonedDateTime fuelDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId fuelId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Fuel"),
                        OUTFLOW,
                        Money.of(40 + random.nextInt(60), "USD"),
                        fuelDate,
                        fuelDate
                );

                if (isAttestedMonth && random.nextDouble() < 0.9) {
                    actor.confirmCashChange(cashFlowId, fuelId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = fuelId;
            }

            // Car insurance (once a year)
            if (monthOffset % 12 == 0) {
                ZonedDateTime insuranceDate = currentPeriod.atDay(15).atStartOfDay(ZoneOffset.UTC);
                CashChangeId insuranceId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Car Insurance"),
                        OUTFLOW,
                        Money.of(800 + random.nextInt(500), "USD"),
                        insuranceDate,
                        insuranceDate.plusDays(14)
                );

                if (isAttestedMonth) {
                    actor.confirmCashChange(cashFlowId, insuranceId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = insuranceId;
            }

            // Car service (occasional)
            if (random.nextDouble() < 0.15) {
                ZonedDateTime serviceDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId serviceId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Car Service"),
                        OUTFLOW,
                        Money.of(100 + random.nextInt(800), "USD"),
                        serviceDate,
                        serviceDate.plusDays(7)
                );

                // Edit if unexpected costs
                if (random.nextDouble() < 0.3) {
                    actor.editCashChange(
                            cashFlowId,
                            serviceId,
                            new Name("Car Service - additional repairs"),
                            new Description("Additional issues found during service"),
                            Money.of(200 + random.nextInt(1000), "USD"),
                            serviceDate.plusDays(10)
                    );
                }

                if (isAttestedMonth && random.nextDouble() < 0.85) {
                    actor.confirmCashChange(cashFlowId, serviceId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = serviceId;
            }

            // Public transport (occasional)
            if (random.nextDouble() < 0.4) {
                ZonedDateTime ptDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId ptId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Public Transit"),
                        OUTFLOW,
                        Money.of(5 + random.nextInt(50), "USD"),
                        ptDate,
                        ptDate
                );

                if (isAttestedMonth && random.nextDouble() < 0.9) {
                    actor.confirmCashChange(cashFlowId, ptId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = ptId;
            }

            // Health - gym membership (monthly)
            if (random.nextDouble() < 0.7) {
                ZonedDateTime gymDate = currentPeriod.atDay(1).atStartOfDay(ZoneOffset.UTC);
                CashChangeId gymId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Gym"),
                        OUTFLOW,
                        Money.of(30 + random.nextInt(50), "USD"),
                        gymDate,
                        gymDate.plusDays(5)
                );

                if (isAttestedMonth && random.nextDouble() < 0.95) {
                    actor.confirmCashChange(cashFlowId, gymId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = gymId;
            }

            // Medicine (occasional)
            if (random.nextDouble() < 0.3) {
                ZonedDateTime medDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId medId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Medicine"),
                        OUTFLOW,
                        Money.of(10 + random.nextInt(100), "USD"),
                        medDate,
                        medDate.plusDays(3)
                );

                if (isAttestedMonth && random.nextDouble() < 0.9) {
                    actor.confirmCashChange(cashFlowId, medId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = medId;
            }

            // Doctor visits (occasional)
            if (random.nextDouble() < 0.2) {
                ZonedDateTime docDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId docId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Doctor Visits"),
                        OUTFLOW,
                        Money.of(50 + random.nextInt(200), "USD"),
                        docDate,
                        docDate.plusDays(7)
                );

                if (isAttestedMonth && random.nextDouble() < 0.85) {
                    actor.confirmCashChange(cashFlowId, docId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = docId;
            }

            // Entertainment - streaming (monthly)
            for (String streaming : List.of("Netflix", "Spotify", "HBO")) {
                if (random.nextDouble() < 0.6) {
                    ZonedDateTime streamDate = currentPeriod.atDay(1 + random.nextInt(10)).atStartOfDay(ZoneOffset.UTC);
                    CashChangeId streamId = actor.appendCashChange(
                            cashFlowId,
                            new CategoryName("Streaming"),
                            OUTFLOW,
                            Money.of(10 + random.nextInt(20), "USD"),
                            streamDate,
                            streamDate.plusDays(3)
                    );

                    if (isAttestedMonth && random.nextDouble() < 0.95) {
                        actor.confirmCashChange(cashFlowId, streamId);
                        lastPaymentStatus = PaymentStatus.PAID;
                    } else {
                        lastPaymentStatus = PaymentStatus.EXPECTED;
                    }
                    lastCashChangeId = streamId;
                }
            }

            // Cinema (occasional)
            if (random.nextDouble() < 0.3) {
                ZonedDateTime cinemaDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId cinemaId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Cinema"),
                        OUTFLOW,
                        Money.of(15 + random.nextInt(35), "USD"),
                        cinemaDate,
                        cinemaDate
                );

                if (isAttestedMonth && random.nextDouble() < 0.9) {
                    actor.confirmCashChange(cashFlowId, cinemaId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = cinemaId;
            }

            // Games (occasional)
            if (random.nextDouble() < 0.15) {
                ZonedDateTime gameDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId gameId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Games"),
                        OUTFLOW,
                        Money.of(20 + random.nextInt(80), "USD"),
                        gameDate,
                        gameDate.plusDays(1)
                );

                if (isAttestedMonth && random.nextDouble() < 0.9) {
                    actor.confirmCashChange(cashFlowId, gameId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = gameId;
            }

            // Books (occasional)
            if (random.nextDouble() < 0.25) {
                ZonedDateTime bookDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId bookId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Books"),
                        OUTFLOW,
                        Money.of(10 + random.nextInt(40), "USD"),
                        bookDate,
                        bookDate.plusDays(5)
                );

                if (isAttestedMonth && random.nextDouble() < 0.9) {
                    actor.confirmCashChange(cashFlowId, bookId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = bookId;
            }

            // Education - courses (occasional)
            if (random.nextDouble() < 0.1) {
                ZonedDateTime courseDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId courseId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Courses"),
                        OUTFLOW,
                        Money.of(50 + random.nextInt(200), "USD"),
                        courseDate,
                        courseDate.plusDays(14)
                );

                // Sometimes edit to different amount
                if (random.nextDouble() < 0.2) {
                    actor.editCashChange(
                            cashFlowId,
                            courseId,
                            new Name("Online Course - discounted"),
                            new Description("Applied discount code"),
                            Money.of(30 + random.nextInt(150), "USD"),
                            courseDate.plusDays(7)
                    );
                }

                if (isAttestedMonth && random.nextDouble() < 0.85) {
                    actor.confirmCashChange(cashFlowId, courseId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = courseId;
            }

            // Subscriptions
            if (random.nextDouble() < 0.5) {
                ZonedDateTime subDate = currentPeriod.atDay(1 + random.nextInt(10)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId subId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Subscriptions"),
                        OUTFLOW,
                        Money.of(5 + random.nextInt(50), "USD"),
                        subDate,
                        subDate.plusDays(5)
                );

                if (isAttestedMonth && random.nextDouble() < 0.95) {
                    actor.confirmCashChange(cashFlowId, subId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = subId;
            }

            // Clothes (occasional)
            if (random.nextDouble() < 0.2) {
                CategoryName clothesCategory = random.nextBoolean() ? new CategoryName("Apparel") : new CategoryName("Footwear");
                ZonedDateTime clothesDate = currentPeriod.atDay(1 + random.nextInt(27)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId clothesId = actor.appendCashChange(
                        cashFlowId,
                        clothesCategory,
                        OUTFLOW,
                        Money.of(50 + random.nextInt(200), "USD"),
                        clothesDate,
                        clothesDate.plusDays(7)
                );

                if (isAttestedMonth && random.nextDouble() < 0.85) {
                    actor.confirmCashChange(cashFlowId, clothesId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = clothesId;
            }

            // Savings (monthly)
            if (random.nextDouble() < 0.6) {
                ZonedDateTime savingsDate = currentPeriod.atDay(25 + random.nextInt(3)).atStartOfDay(ZoneOffset.UTC);
                CashChangeId savingsId = actor.appendCashChange(
                        cashFlowId,
                        new CategoryName("Savings"),
                        OUTFLOW,
                        Money.of(200 + random.nextInt(800), "USD"),
                        savingsDate,
                        savingsDate.plusDays(3)
                );

                if (isAttestedMonth && random.nextDouble() < 0.9) {
                    actor.confirmCashChange(cashFlowId, savingsId);
                    lastPaymentStatus = PaymentStatus.PAID;
                } else {
                    lastPaymentStatus = PaymentStatus.EXPECTED;
                }
                lastCashChangeId = savingsId;
            }

            // Attest the month if needed
            if (isAttestedMonth) {
                YearMonth attestPeriod = currentPeriod.plusMonths(1);
                actor.attestMonth(
                        cashFlowId,
                        attestPeriod,
                        Money.zero("USD"),
                        attestPeriod.atEndOfMonth().atStartOfDay(ZoneOffset.UTC)
                );
            }

            lastPeriod = currentPeriod;
        }

        // Wait for cashflow to be processed
        await().until(() -> statementRepository.findByCashFlowId(cashFlowId).isPresent());

        // Wait for last transaction to be processed
        CashChangeId finalLastCashChangeId = lastCashChangeId;
        PaymentStatus finalLastPaymentStatus = lastPaymentStatus;
        YearMonth finalLastPeriod = lastPeriod;

        await().atMost(30, SECONDS).until(() -> cashChangeInStatusHasBeenProcessed(cashFlowId, finalLastPeriod, finalLastCashChangeId, finalLastPaymentStatus));

        log.info("Generated cashflow statement: {}", JsonContent.asJson(statementRepository.findByCashFlowId(cashFlowId).get()).content());
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
class HomeBudgetActor {

    private CashFlowRestController cashFlowRestController;
    private CommandGateway commandGateway;

    CashFlowId createCashFlow() {
        return new CashFlowId(cashFlowRestController.createCashFlow(
                CashFlowDto.CreateCashFlowJson.builder()
                        .userId("home-budget-user")
                        .name("Home Budget")
                        .description("Comprehensive home budget with multiple categories")
                        .bankAccount(new BankAccount(
                                new BankName("Chase Bank"),
                                new BankAccountNumber("US12345678901234567890", Currency.of("USD")),
                                Money.of(10000, "USD")))
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
}
