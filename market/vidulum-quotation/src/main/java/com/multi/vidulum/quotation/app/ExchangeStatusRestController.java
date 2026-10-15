package com.multi.vidulum.quotation.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.quotation.domain.QuotationService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Whether an exchange can be used right now (task B6).
 *
 * <p>The prototype calls this before onboarding: quotes have to be in the cache <b>before</b> a
 * portfolio exists, because {@code GET /portfolio} prices every asset it holds and a missing one
 * throws. Finding that out afterwards means a portfolio nobody can value.
 *
 * <p>Answers for an unregistered broker instead of 404: "we do not serve that exchange" is the
 * useful answer to "can I use it", and a 404 would make the caller guess whether the endpoint
 * itself was wrong.
 */
@RestController
@AllArgsConstructor
public class ExchangeStatusRestController {

    private final QuotationService quotationService;
    private final Clock clock;

    @GetMapping("/exchange/status")
    public ExchangeStatusDto.ExchangeStatusListJson all() {
        List<ExchangeStatusDto.ExchangeStatusJson> exchanges = quotationService.registeredBrokers()
                .stream()
                .map(this::statusOf)
                .sorted(Comparator.comparing(ExchangeStatusDto.ExchangeStatusJson::exchange))
                .toList();
        return new ExchangeStatusDto.ExchangeStatusListJson(exchanges);
    }

    @GetMapping("/exchange/{name}/status")
    public ExchangeStatusDto.ExchangeStatusJson one(@PathVariable String name) {
        Broker broker = Broker.of(name);
        if (!quotationService.isRegistered(broker)) {
            return new ExchangeStatusDto.ExchangeStatusJson(
                    broker.getId(),
                    Reachability.UNKNOWN.name(),
                    false,
                    false,
                    List.of(),
                    "No quotation provider is registered for this exchange",
                    ZonedDateTime.now(clock));
        }
        return statusOf(broker);
    }

    private ExchangeStatusDto.ExchangeStatusJson statusOf(Broker broker) {
        List<String> symbols = quotationService.quotedSymbols(broker).stream()
                .map(Symbol::getId)
                .sorted()
                .toList();

        return new ExchangeStatusDto.ExchangeStatusJson(
                broker.getId(),
                Reachability.UNKNOWN.name(),
                true,
                !symbols.isEmpty(),
                symbols,
                // Said plainly rather than left for the reader to infer from UNKNOWN.
                "Reachability is not probed yet; brokerRegistered and quotesReady are live",
                ZonedDateTime.now(clock));
    }
}
