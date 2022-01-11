package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.*;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OrderStatement {
    OrderId orderId;
    Symbol symbol;
    OrderType type;
    Side side;
    Money stopPrice;
    Money limitPrice;
    Quantity quantity;
    Money total;
//    String description; TODO fill in future
}
