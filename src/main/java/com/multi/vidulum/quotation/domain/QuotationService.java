package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import lombok.AllArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@AllArgsConstructor
public class QuotationService {
    private final Map<Broker, BrokerQuotationProvider> registeredBrokers = new ConcurrentHashMap<>();

    public void registerBroker(BrokerQuotationProvider brokerQuotationProvider) {
        registeredBrokers.putIfAbsent(brokerQuotationProvider.getBroker(), brokerQuotationProvider);
    }

    @KafkaListener(
            groupId = "group_id1",
            topics = "quotes",
            containerFactory = "priceChangingContainerFactory")
    public void onPriceChange(PriceChangedEvent event) {
        findBrokerOrRaiseException(event.getBroker(), brokerProvider -> {
            brokerProvider.onPriceChange(event);
            return null;
        });
    }

    public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
        return findBrokerOrRaiseException(broker, brokerProvider -> brokerProvider.fetch(symbol));
    }

    public AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker) {
        return findBrokerOrRaiseException(broker, brokerProvider -> brokerProvider.fetchBasicInfoAboutAsset(ticker));
    }

    private <R> R findBrokerOrRaiseException(Broker broker, Function<BrokerQuotationProvider, R> callback) {
        if (registeredBrokers.containsKey(broker)) {
            BrokerQuotationProvider brokerQuotationProvider = registeredBrokers.get(broker);
            return callback.apply(brokerQuotationProvider);
        } else {
            throw new BrokerNotFoundException(broker);
        }
    }
}
