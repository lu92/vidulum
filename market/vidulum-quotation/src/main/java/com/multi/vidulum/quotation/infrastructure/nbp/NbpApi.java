package com.multi.vidulum.quotation.infrastructure.nbp;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The public rate API of the Polish central bank. No key, no account, no rate limit worth a
 * backoff - which is exactly why the backend may hold this itself while exchange credentials stay
 * outside.
 *
 * <p>Table A is the daily average rate of the major currencies against the złoty.
 */
@HttpExchange("/api/exchangerates")
public interface NbpApi {

    /** @param code lowercase ISO code, as the API's path expects it ("usd", "eur"). */
    @GetExchange("/rates/a/{code}/")
    TableA averageRate(@PathVariable("code") String code);

    record TableA(String table, String currency, String code, List<Rate> rates) {
        public record Rate(String no, LocalDate effectiveDate, BigDecimal mid) {
        }
    }
}
