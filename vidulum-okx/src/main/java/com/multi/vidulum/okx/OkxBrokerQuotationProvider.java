package com.multi.vidulum.okx;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.quotation.api.BrokerQuotationProvider;

/**
 * Quotation provider for the OKX exchange.
 *
 * <p>Deliberately empty beyond the broker identity: caching, lookup and the USD/USDT fallback all
 * live in {@link BrokerQuotationProvider} and are {@code final} there, so no provider — in this
 * module or any other — can override them.
 *
 * <p>One broker covers both the demo and live OKX environments. Prices differ between them, and
 * {@code QuotationService} keeps a single cache per broker, so running both against one instance
 * would mix them. Splitting into two brokers is deferred until that actually happens.
 */
public class OkxBrokerQuotationProvider extends BrokerQuotationProvider {

    public static final Broker OKX = Broker.of("OKX");

    public OkxBrokerQuotationProvider() {
        super(OKX);
    }
}
