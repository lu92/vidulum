package com.multi.vidulum.quotation.app;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.quotation.domain.PriceChangedEvent;
import com.multi.vidulum.quotation.domain.QuotationService;
import lombok.AllArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;

@AllArgsConstructor
@RestController
public class QuoteRestController {

    private final QuotationService quotationService;
    private final KafkaTemplate<String, PriceChangedEvent> pricingKafkaTemplate;

    @GetMapping(value = "/quote/{ticker}")
    public AssetPriceMetadata fetch(@PathVariable("ticker") String ticker) {
        return quotationService.fetch(Ticker.of(ticker.toUpperCase()));
    }

    @GetMapping(value = "/quote/publish")
    void changePrice(@RequestParam String ticker, double amount, String currency, double pctChange) {
        PriceChangedEvent priceChangedEvent = PriceChangedEvent.builder()
                .ticker(Ticker.of(ticker))
                .currentPrice(Money.of(amount, currency))
                .pctChange(pctChange)
                .dateTime(ZonedDateTime.now())
                .build();
        pricingKafkaTemplate.send("quotes", priceChangedEvent);
    }
}
