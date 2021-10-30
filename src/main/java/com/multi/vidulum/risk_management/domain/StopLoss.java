package com.multi.vidulum.risk_management.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Symbol;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@Builder
public class StopLoss {
    Symbol symbol;
    Quantity quantity;
    Money price;
    boolean isApplicable;
    ZonedDateTime dateTime;
}
