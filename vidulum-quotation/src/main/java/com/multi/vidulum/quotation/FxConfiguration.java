package com.multi.vidulum.quotation;

import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.quotation.app.FxRateRefresher;
import com.multi.vidulum.quotation.domain.fx.FxRateSource;
import com.multi.vidulum.quotation.domain.fx.FxRates;
import com.multi.vidulum.quotation.infrastructure.nbp.NbpApi;
import com.multi.vidulum.quotation.infrastructure.nbp.NbpFxRateSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.List;

/**
 * Currency rates the backend keeps for itself.
 *
 * <p>The keeper is always a bean: quote lookup needs somewhere to read rates from whether or not
 * anything is filling it, and an empty keeper is a legitimate state (nothing fetched yet), not a
 * disabled feature.
 *
 * <p>Fetching is what {@code vidulum.fx.enabled} turns off — set to {@code false} in tests, which
 * have no business reaching the central bank, exactly as the cashflow HTTP clients are switched
 * off there.
 *
 * <p>Properties: {@code vidulum.fx.enabled}, {@code vidulum.fx.nbp.base-url},
 * {@code vidulum.fx.pairs} (the pairs to hold), {@code vidulum.fx.cron} (how often to refresh).
 */
@Configuration
public class FxConfiguration {

    @Value("${vidulum.fx.nbp.base-url:https://api.nbp.pl}")
    private String nbpBaseUrl;

    @Bean
    public FxRates fxRates() {
        return new FxRates();
    }

    @Bean
    @ConditionalOnProperty(name = "vidulum.fx.enabled", havingValue = "true", matchIfMissing = true)
    public NbpApi nbpApi(RestClient.Builder restClientBuilder) {
        RestClient restClient = restClientBuilder
                .baseUrl(nbpBaseUrl)
                // The API answers XML unless asked otherwise, and asks for no credentials at all.
                .defaultHeader("Accept", "application/json")
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(NbpApi.class);
    }

    @Bean
    @ConditionalOnProperty(name = "vidulum.fx.enabled", havingValue = "true", matchIfMissing = true)
    public FxRateSource nbpFxRateSource(NbpApi nbpApi) {
        return new NbpFxRateSource(nbpApi);
    }

    @Bean
    @ConditionalOnProperty(name = "vidulum.fx.enabled", havingValue = "true", matchIfMissing = true)
    public FxRateRefresher fxRateRefresher(
            FxRateSource source,
            FxRates fxRates,
            @Value("${vidulum.fx.pairs:USD/PLN}") List<String> pairs) {
        return new FxRateRefresher(source, fxRates, pairs.stream().map(Symbol::of).toList());
    }
}
