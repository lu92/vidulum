package com.multi.vidulum.quotation.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.quotation.domain.PriceChangedEvent;
import com.multi.vidulum.quotation.domain.QuotationService;
import lombok.AllArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;

@RestController
@AllArgsConstructor
public class QuoteRestController {

    private final QuotationService quotationService;
    private final KafkaTemplate<String, PriceChangedEvent> pricingKafkaTemplate;

    @GetMapping(value = "/quote/{broker}/{origin}/{destination}")
    public AssetPriceMetadata fetch(
            @PathVariable("broker") String broker,
            @PathVariable("origin") String origin,
            @PathVariable("destination") String destination) {
        return quotationService.fetch(Broker.of(broker), Symbol.of(Ticker.of(origin), Ticker.of(destination)));
    }

    @GetMapping(value = "/quote/publish")
    public void changePrice(@RequestParam String broker,  String origin, String destination, double amount, String currency, double pctChange) {
        PriceChangedEvent priceChangedEvent = PriceChangedEvent.builder()
                .broker(Broker.of(broker))
                .symbol(Symbol.of(Ticker.of(origin), Ticker.of(destination)))
                .currentPrice(Money.of(amount, currency))
                .pctChange(pctChange)
                .dateTime(ZonedDateTime.now())
                .build();
        pricingKafkaTemplate.send("quotes", priceChangedEvent);
    }
}
