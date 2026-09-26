package com.multi.vidulum.quotation.fx;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.infrastructure.nbp.NbpApi;
import com.multi.vidulum.quotation.infrastructure.nbp.NbpFxRateSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading the central bank's table (task B4).
 *
 * <p>Driven through a stub of the HTTP interface rather than a mock server: what is worth pinning
 * here is the mapping and the two shapes of "no", and a real socket would only add flakiness to
 * assertions about neither.
 */
class NbpFxRateSourceTest {

    /** Records what was asked, so a test can assert that nothing was asked at all. */
    private static final class StubApi implements NbpApi {
        private final List<String> asked = new ArrayList<>();
        private Function<String, TableA> answer = code -> null;

        @Override
        public TableA averageRate(String code) {
            asked.add(code);
            return answer.apply(code);
        }
    }

    private final StubApi api = new StubApi();
    private final NbpFxRateSource source = new NbpFxRateSource(api);

    private static NbpApi.TableA tableA(String code, String date, String mid) {
        return new NbpApi.TableA("A", "dolar amerykański", code.toUpperCase(),
                List.of(new NbpApi.TableA.Rate("185/A/NBP/2026", LocalDate.parse(date), new BigDecimal(mid))));
    }

    @Test
    void shouldReadTheLatestFixingAgainstTheZloty() {
        api.answer = code -> tableA(code, "2026-09-25", "3.6455");

        Optional<FxRate> rate = source.latest(Currency.of("USD"), Currency.of("PLN"));

        assertThat(rate).isPresent();
        assertThat(rate.get().symbol().getId()).isEqualTo("USD/PLN");
        assertThat(rate.get().rate()).isEqualTo(Price.of(3.6455, "PLN"));
        assertThat(rate.get().source()).isEqualTo(NbpFxRateSource.NBP);
        assertThat(api.asked)
                .as("the path wants the code in lower case")
                .containsExactly("usd");
    }

    /**
     * The moment carried is the day the rate applies to, at its start in Warsaw — not the instant
     * we happened to read it. A fixing is a day's rate; stamping it with "now" would make a
     * Saturday read of Friday's rate look current, and the chain reports a price as being exactly
     * as fresh as its stalest leg.
     */
    @Test
    void shouldCarryTheDayTheRateAppliesToRatherThanTheMomentItWasRead() {
        api.answer = code -> tableA(code, "2026-09-25", "3.6455");

        Optional<FxRate> rate = source.latest(Currency.of("USD"), Currency.of("PLN"));

        assertThat(rate.get().asOf())
                .isEqualTo(ZonedDateTime.parse("2026-09-25T00:00+02:00[Europe/Warsaw]"));
    }

    /**
     * This institution publishes against the złoty and nothing else. Asking it for USD/EUR is a
     * category error, not an outage — so it answers empty, without a call, and the caller is not
     * invited to retry.
     */
    @Test
    void shouldNotAnswerForPairsThatDoNotInvolveTheZloty() {
        assertThat(source.latest(Currency.of("USD"), Currency.of("EUR"))).isEmpty();
        assertThat(source.latest(Currency.of("PLN"), Currency.of("PLN"))).isEmpty();
        assertThat(api.asked).isEmpty();
    }

    /** A currency the bank does not list arrives as a 404 — again "not published", not "broken". */
    @Test
    void shouldTreatAnUnlistedCurrencyAsNothingPublished() {
        api.answer = code -> {
            throw HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
        };

        assertThat(source.latest(Currency.of("XYZ"), Currency.of("PLN"))).isEmpty();
    }

    @Test
    void shouldTreatAnEmptyTableAsNothingPublished() {
        api.answer = code -> new NbpApi.TableA("A", "waluta", code.toUpperCase(), List.of());

        assertThat(source.latest(Currency.of("USD"), Currency.of("PLN"))).isEmpty();
    }

    /**
     * An unreachable bank is not the same fact as a currency nobody quotes, and flattening the two
     * into an empty answer would hide an outage behind "we do not publish that". It is thrown, so
     * the refresher can log it, keep yesterday's rate and try again.
     */
    @Test
    void shouldLetAnOutageThrough() {
        api.answer = code -> {
            throw HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", HttpHeaders.EMPTY, new byte[0], null);
        };

        assertThatThrownBy(() -> source.latest(Currency.of("USD"), Currency.of("PLN")))
                .isInstanceOf(HttpServerErrorException.class);
    }
}
