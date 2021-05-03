package com.multi.vidulum.quotation.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.quotation.domain.BrokerQuotationProvider;

public class BinanceBrokerQuotationProvider extends BrokerQuotationProvider {
    public BinanceBrokerQuotationProvider() {
        super(Broker.of("BINANCE"));
    }
}
