package com.multi.vidulum.okx;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.quotation.domain.BrokerQuotationProvider;

/**
 * Quotation provider for the OKX exchange.
 *
 * <p>Deliberately empty beyond the broker identity: caching, lookup and the USD/USDT fallback all
 * live in {@link BrokerQuotationProvider}, and those members are package-private to
 * {@code com.multi.vidulum.quotation.domain}, so no provider outside that package can override
 * them. None of the existing providers do either.
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
