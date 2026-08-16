package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.OrderId;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(OrderId orderId) {
        super(String.format("Order [%s] not found", orderId.getId()));
    }
}
