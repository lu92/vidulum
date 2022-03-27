package com.multi.vidulum.trading;

import com.multi.vidulum.common.OrderId;

public class OrderIsNotOpenException extends RuntimeException {
    public OrderIsNotOpenException(OrderId orderId) {
        super(String.format("Order [%s] is not open", orderId.getId()));
    }
}
