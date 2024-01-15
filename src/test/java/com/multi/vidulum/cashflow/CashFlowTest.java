package com.multi.vidulum.cashflow;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.*;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.time.Month;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.multi.vidulum.cashflow.MoneyFlowType.*;

class CashFlowTest {

    @Test
    void test() {
        // given
        CashFlow cashFlow = new CashFlowFactory().crete(
                "some name",
                UserId.of("user1"),
                new BankAccount(new BankAccountNumber("123456789"), Money.zero("PLN"))
        );

        cashFlow.plus(
                new InFlow(
                        MoneyFlowId.generate(),
                        "invoice 01/2012",
                        Category.of("main income"),
                        Money.of(23000, "PLN"),
                        ACTUAL,
                        ZonedDateTime.parse("2022-05-20T15:39:41Z")
                )
        );

        cashFlow.plus(
                new InFlow(
                        MoneyFlowId.generate(),
                        "manual work",
                        Category.of("extra income"),
                        Money.of(1000, "PLN"),
                        ACTUAL,
                        ZonedDateTime.parse("2022-05-20T15:39:41Z")
                )
        );

        cashFlow.plus(
                new InFlow(
                        MoneyFlowId.generate(),
                        "invoice 02/2012",
                        Category.of("main income"),
                        Money.of(23000, "PLN"),
                        ACTUAL,
                        ZonedDateTime.parse("2022-06-20T15:39:41Z")
                )
        );

        cashFlow.minus(
                new OutFlow(
                        MoneyFlowId.generate(),
                        "zus",
                        Category.of("tax"),
                        Money.of(2000, "PLN"),
                        ACTUAL,
                        ZonedDateTime.parse("2022-05-20T15:39:41Z")
                )
        );

        cashFlow.minus(
                new OutFlow(
                        MoneyFlowId.generate(),
                        "loan ikano",
                        Category.of("loan"),
                        Money.of(1400, "PLN"),
                        ACTUAL,
                        ZonedDateTime.parse("2022-05-25T15:39:41Z")
                )
        );

        cashFlow.minus(
                new OutFlow(
                        MoneyFlowId.generate(),
                        "loan mbank",
                        Category.of("loan"),
                        Money.of(4500, "PLN"),
                        ACTUAL,
                        ZonedDateTime.parse("2022-05-25T15:39:41Z")
                )
        );

        CashFlowForecast cashFlowForecast = new ForecastProcessor().generateForecast(cashFlow,
                YearMonth.of(2022, Month.MAY),
                YearMonth.of(2022, Month.JUNE));

        System.out.println(cashFlowForecast);
    }
}

class MismatchedCurrencyException extends RuntimeException {
    private final Currency expected;

    public MismatchedCurrencyException(Currency expected) {
        super(String.format("Attempt of usage of incorrect currency [expected: %s]", expected));
        this.expected = expected;
    }
}

record BankAccount(BankAccountNumber bankAccountNumber, Money money) {

}

record BankAccountNumber(String number) {
}

record CashFlowForecast(
        List<CashFlowMonthlyForecast> data,
        YearMonth start,
        YearMonth end) {
}

record CashFlowMonthlyForecast(
        YearMonth period,
        List<CashCategory> categorizedInFlows,
        List<CashCategory> categorizedOutFlows,
        Money openingBalance,
        CashSummary inFlowCashSummary,
        CashSummary outFlowCashSummary) {
}

record CashCategory(
        Category category,
        Money value) {
}

record CashSummary(
        Money actual,
        Money expected,
        Money gapToForecast) {
}

sealed interface MoneyFlow permits InFlow, OutFlow {
    MoneyFlowId id();

    String name();

    Category category();

    Money money();

    MoneyFlowType type();

    ZonedDateTime dateTime();
}

record InFlow(
        MoneyFlowId id,
        String name,
        Category category,
        Money money,
        MoneyFlowType type,
        ZonedDateTime dateTime
) implements MoneyFlow {

}

record OutFlow(
        MoneyFlowId id,
        String name,
        Category category,
        Money money,
        MoneyFlowType type,
        ZonedDateTime dateTime
) implements MoneyFlow {
}

record CashTransaction(
        MoneyFlowId id,
        CashOperation cashOperation,
        ZonedDateTime created,
        ZonedDateTime dueDate,
        ZonedDateTime endDate
) {}

enum CashOperation {
    INFLOW, OUTFLOW
}

enum MoneyFlowType {
    ACTUAL, EXPECTED, GAP_TO_FORECAST;
}
// operacje na inflow/outflow to:
// confirm(id, money)
// reject(id, reason)
// comment(id, comment) // dodanie komentarza do in/out flow
// remove(id, comment)

// *reopen(id, comment)

enum MoneyFlowState {
    OPEN, CONFIRMED, REJECTED

}