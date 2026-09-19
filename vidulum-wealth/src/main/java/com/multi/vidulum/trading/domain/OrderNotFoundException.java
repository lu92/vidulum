package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class OrderNotFoundException extends BusinessException {
    public OrderNotFoundException(OrderId orderId) {
        super(String.format("Order [%s] not found", orderId.getId()));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.ORDER_NOT_FOUND;
    }
}
