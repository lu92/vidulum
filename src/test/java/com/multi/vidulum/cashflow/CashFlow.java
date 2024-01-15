package com.multi.vidulum.cashflow;

import com.multi.vidulum.common.*;
import lombok.Value;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Value
class CashFlow {
    CashFlowId id;
    String name;
    //        Currency denomination;
    BankAccount bankAccount;
    UserId userId;
    Map<YearMonth, List<InFlow>> inFlowData;
    Map<YearMonth, List<OutFlow>> outFlowData;
    boolean isEnabled;

    // TotalCashInFlowInPeriod = (...)

    void plus(InFlow inFlow) {
        if (!bankAccount.money().getCurrency().equals(inFlow.money().getCurrency())) {
            throw new MismatchedCurrencyException(Currency.of(bankAccount.money().getCurrency()));
        }
        YearMonth period = YearMonth.from(inFlow.dateTime());
        List<InFlow> presentInflows = inFlowData.getOrDefault(period, new LinkedList<>());
        inFlowData.compute(period, (yearMonth, inFlows) -> {
            presentInflows.add(inFlow);
            return presentInflows;
        });
    }

    void confirmInflow(MoneyFlowId id, Money finalPrice) {
//            inFlowData.
    }

    void editInflow() {

    }

    void minus(OutFlow outFlow) {
        if (!bankAccount.money().getCurrency().equals(outFlow.money().getCurrency())) {
            throw new MismatchedCurrencyException(Currency.of(bankAccount.money().getCurrency()));
        }

        YearMonth period = YearMonth.from(outFlow.dateTime());
        List<OutFlow> presentOutflows = outFlowData.getOrDefault(period, new LinkedList<>());
        outFlowData.compute(period, (yearMonth, inFlows) -> {
            presentOutflows.add(outFlow);
            return presentOutflows;
        });
    }

    public CashFlowStatement generateStatement(ZonedDateTime start, ZonedDateTime end) {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    void change(BankAccount bankAccount) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
