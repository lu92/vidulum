package com.multi.vidulum.risk_management.domain;

import com.multi.vidulum.common.OriginOrderId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Symbol;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@Builder
public class StopLoss {
    Symbol symbol;
    OriginOrderId originOrderId;
    Quantity quantity;
    Price price;
    boolean isApplicable;
    ZonedDateTime dateTime;
}
