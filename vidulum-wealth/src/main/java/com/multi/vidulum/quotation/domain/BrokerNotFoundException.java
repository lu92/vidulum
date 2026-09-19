package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * The broker has no registered {@code BrokerQuotationProvider}. A 404 rather than a 500: the
 * caller asked about an exchange this instance does not serve, which is a bad request, not a
 * failure on our side.
 */
public class BrokerNotFoundException extends BusinessException {

    public BrokerNotFoundException(Broker broker) {
        super(String.format("Broker [%s] not found!", broker.getId()));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.BROKER_NOT_FOUND;
    }
}
