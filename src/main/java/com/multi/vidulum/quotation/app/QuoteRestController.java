package com.multi.vidulum.quotation.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.quotation.domain.PriceChangedEvent;
import com.multi.vidulum.quotation.domain.QuotationService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;

@Slf4j
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

    @PutMapping(value = "/quote/{broker}/")
    public void registerAssetBasicInfo(@PathVariable("broker") String broker, @RequestBody QuotationDto.AssetBasicInfoJson assetBasicInfoJson) {
        AssetBasicInfo assetBasicInfo = AssetBasicInfo.builder()
                .ticker(Ticker.of(assetBasicInfoJson.getTicker()))
                .fullName(assetBasicInfoJson.getFullName())
                .segment(Segment.of(assetBasicInfoJson.getSegment()))
                .tags(assetBasicInfoJson.getTags())
                .build();
        quotationService.registerAssetBasicInfo(Broker.of(broker), assetBasicInfo);
    }

    @GetMapping(value = "/quote/publish")
    public void changePrice(@RequestParam String broker, String origin, String destination, double amount, String currency, double pctChange) {
        PriceChangedEvent priceChangedEvent = PriceChangedEvent.builder()
                .broker(Broker.of(broker))
                .symbol(Symbol.of(Ticker.of(origin), Ticker.of(destination)))
                .currentPrice(Price.of(amount, currency))
                .pctChange(pctChange)
                .dateTime(ZonedDateTime.now())
                .build();
        pricingKafkaTemplate.send("quotes", priceChangedEvent);
    }

    @GetMapping(value = "/quote/clearCaches")
    public void clearCaches() {
        quotationService.clearCaches();
        log.info("Caches have been cleared");

    }
}
