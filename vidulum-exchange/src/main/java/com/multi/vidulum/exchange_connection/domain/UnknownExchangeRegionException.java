package com.multi.vidulum.exchange_connection.domain;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

import java.util.Collection;

/**
 * The region is not one this exchange serves. Raised by an {@link ExchangeAdapter}, which owns
 * the vocabulary; the message lists the accepted values because they differ per exchange and the
 * caller has no other way to learn them.
 */
public class UnknownExchangeRegionException extends BusinessException {

    public UnknownExchangeRegionException(Broker broker, String region, Collection<String> supported) {
        super("Region [" + region + "] is not served by " + broker.getId() + "; expected one of: "
                + String.join(", ", supported));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.EXCHANGE_REGION_UNKNOWN;
    }
}
