package com.multi.vidulum.exchange_connection.domain;

import com.multi.vidulum.common.Broker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of the exchanges this instance can connect to.
 *
 * <p>Adapters are collected from the context the same way {@code KafkaTopicConfig} collects
 * quotation providers, so adding an exchange means adding a module rather than editing this one.
 * The registry is also the answer to "which exchanges are supported", which the list query needs
 * and the connect command needs anyway in order to reject an unknown broker.
 */
@Slf4j
@Component
public class ExchangeAdapters {

    private final Map<String, ExchangeAdapter> byBroker = new LinkedHashMap<>();

    public ExchangeAdapters(List<ExchangeAdapter> registered) {
        registered.forEach(adapter -> byBroker.put(key(adapter.broker()), adapter));
        log.info("Exchange adapters registered: {}", byBroker.keySet());
    }

    /**
     * @throws ExchangeNotSupportedException when no module registered this broker
     */
    public ExchangeAdapter require(Broker broker) {
        ExchangeAdapter adapter = byBroker.get(key(broker));
        if (adapter == null) {
            throw new ExchangeNotSupportedException(broker, byBroker.keySet());
        }
        return adapter;
    }

    public List<String> supportedExchanges() {
        return List.copyOf(byBroker.keySet());
    }

    private static String key(Broker broker) {
        return broker.getId().toUpperCase(Locale.ROOT);
    }
}
