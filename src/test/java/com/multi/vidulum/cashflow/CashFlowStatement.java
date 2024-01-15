package com.multi.vidulum.cashflow;

import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;
import java.util.List;


record CashFlowStatement(List<CashTransaction> confirmedTransactions,
                         ZonedDateTime startDateTime,
                         ZonedDateTime endDateTime,
                         Money start,
                         Money end,
                         Money netChange,
                         ZonedDateTime dateTime) {
}
