package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;

public class CannotUnlockAssetException extends RuntimeException {


    public CannotUnlockAssetException(String message) {
        super(message);
    }

    public static CannotUnlockAssetException incorrectOrder(Ticker ticker, OrderId orderId) {
        return new CannotUnlockAssetException(String.format("Cannot unlock [%s] - unable to find [%s]",ticker, orderId));
    }

    public static CannotUnlockAssetException insufficientQuantity(Ticker ticker, OrderId orderId, Quantity quantity) {
        return new CannotUnlockAssetException(String.format("Cannot unlock [%s] for [%s] - insufficient balance [%s]", ticker, orderId, quantity));
    }


}
