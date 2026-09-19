package com.multi.vidulum.okx;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.exchange_connection.domain.ExchangeAdapter;
import com.multi.vidulum.exchange_connection.domain.UnknownExchangeRegionException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Registers OKX with {@code ExchangeConnectionService}. Everything OKX-specific about creating a
 * connection lives here; the rest of the flow is exchange-agnostic.
 */
@Component
public class OkxExchangeAdapter implements ExchangeAdapter {

    @Override
    public Broker broker() {
        return OkxBrokerQuotationProvider.OKX;
    }

    /**
     * OKX serves each region from its own hosts and a key issued for one does not authenticate
     * against another, so an unrecognised region has to be refused at registration rather than
     * discovered later as a 401 from the exchange.
     */
    @Override
    public void validateRegion(String region) {
        String candidate = region == null ? "" : region.trim().toUpperCase(Locale.ROOT);
        boolean known = Arrays.stream(OkxRegion.values())
                .anyMatch(value -> value.name().equals(candidate));
        if (!known) {
            throw new UnknownExchangeRegionException(broker(), region, supportedRegions());
        }
    }

    private static List<String> supportedRegions() {
        return Arrays.stream(OkxRegion.values()).map(Enum::name).toList();
    }
}
