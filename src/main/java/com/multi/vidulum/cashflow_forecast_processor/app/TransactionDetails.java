package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@Builder
public class TransactionDetails {
    private CashChangeId cashChangeId;
    private Name name;
    private Money money;
    private ZonedDateTime created;
    private ZonedDateTime dueDate;
    private ZonedDateTime endDate;
}
