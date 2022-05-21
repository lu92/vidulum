package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioSummaryMapperTest {

    @Mock
    private QuoteRestClient quoteRestClientMock;

    @InjectMocks
    private PortfolioSummaryMapper mapper;

    private static final Portfolio PORTFOLIO;
    private static final Broker BROKER = Broker.of("broker");
    private static final Currency USD = Currency.of("USD");

    static {
        PORTFOLIO = new PortfolioFactory().empty(
                PortfolioId.generate(),
                "XYZ",
                UserId.of("user"),
                BROKER,
                USD);

        PORTFOLIO.depositMoney(Money.of(10000, "USD"));
        PORTFOLIO.lockAsset(Ticker.of("USD"), Quantity.of(4000));
        PORTFOLIO.handleExecutedTrade(
                ExecutedTrade.builder()
                        .portfolioId(PORTFOLIO.getPortfolioId())
                        .tradeId(TradeId.of("trade-1"))
                        .symbol(Symbol.of("BTC/USD"))
                        .subName(SubName.none())
                        .side(Side.BUY)
                        .quantity(Quantity.of(0.1))
                        .price(Price.of(40000.0, "USD"))
                        .build());
    }

    @Test
    public void shouldDenominatePortfolioInOriginCurrency() {
        // Given
        when(quoteRestClientMock.fetch(BROKER, Symbol.of("USD/USD")))
                .thenReturn(AssetPriceMetadata.builder().currentPrice(Price.of(1, "USD")).build());
        when(quoteRestClientMock.fetch(BROKER, Symbol.of("BTC/USD")))
                .thenReturn(AssetPriceMetadata.builder().currentPrice(Price.of(40000, "USD")).build());
        when(quoteRestClientMock.fetchBasicInfoAboutAsset(BROKER, Ticker.of("USD")))
                .thenReturn(AssetBasicInfo.builder()
                        .fullName("American Dollar")
                        .tags(List.of("Cash"))
                        .build());
        when(quoteRestClientMock.fetchBasicInfoAboutAsset(BROKER, Ticker.of("BTC")))
                .thenReturn(AssetBasicInfo.builder()
                        .fullName("Bitcoin")
                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                        .build());
        // When
        PortfolioDto.PortfolioSummaryJson portfolioSummary = mapper.map(PORTFOLIO, Currency.of("USD"));

        // Then
        assertThat(portfolioSummary).isEqualTo(PortfolioDto.PortfolioSummaryJson.builder()
                .portfolioId(PORTFOLIO.getPortfolioId().getId())
                .userId(PORTFOLIO.getUserId().getId())
                .name(PORTFOLIO.getName())
                .broker(PORTFOLIO.getBroker().getId())
                .assets(List.of(
                        PortfolioDto.AssetSummaryJson.builder()
                                .ticker("USD")
                                .fullName("American Dollar")
                                .avgPurchasePrice(Price.one("USD"))
                                .quantity(Quantity.of(6000))
                                .locked(Quantity.of(0))
                                .free(Quantity.of(6000))
                                .pctProfit(0)
                                .profit(Money.zero("USD"))
                                .currentPrice(Price.of(1, "USD"))
                                .currentValue(Money.of(6000, "USD"))
                                .tags(List.of("Cash"))
                                .build(),
                        PortfolioDto.AssetSummaryJson.builder()
                                .ticker("BTC")
                                .fullName("Bitcoin")
                                .avgPurchasePrice(Price.of(40000, "USD"))
                                .quantity(Quantity.of(0.1))
                                .locked(Quantity.of(0))
                                .free(Quantity.of(0.1))
                                .pctProfit(0)
                                .profit(Money.of(0, "USD"))
                                .currentPrice(Price.of(40000, "USD"))
                                .currentValue(Money.of(4000, "USD"))
                                .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                .build()))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(10000, "USD"))
                .currentValue(Money.of(10000, "USD"))
                .pctProfit(0)
                .profit(Money.zero("USD"))
                .build());
    }

    @Test
    public void shouldDenominatePortfolioInOtherCurrency() {
        // Given
        when(quoteRestClientMock.fetch(BROKER, Symbol.of("USD/EUR")))
                .thenReturn(AssetPriceMetadata.builder().currentPrice(Price.of(0.95, "EUR")).build());
        when(quoteRestClientMock.fetch(BROKER, Symbol.of("BTC/EUR")))
                .thenReturn(AssetPriceMetadata.builder().currentPrice(Price.of(38000, "EUR")).build());
        when(quoteRestClientMock.fetchBasicInfoAboutAsset(BROKER, Ticker.of("USD")))
                .thenReturn(AssetBasicInfo.builder()
                        .fullName("American Dollar")
                        .tags(List.of("Cash"))
                        .build());
        when(quoteRestClientMock.fetchBasicInfoAboutAsset(BROKER, Ticker.of("BTC")))
                .thenReturn(AssetBasicInfo.builder()
                        .fullName("Bitcoin")
                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                        .build());
        // When
        PortfolioDto.PortfolioSummaryJson portfolioSummary = mapper.map(PORTFOLIO, Currency.of("EUR"));

        // Then
        assertThat(portfolioSummary).isEqualTo(PortfolioDto.PortfolioSummaryJson.builder()
                .portfolioId(PORTFOLIO.getPortfolioId().getId())
                .userId(PORTFOLIO.getUserId().getId())
                .name(PORTFOLIO.getName())
                .broker(PORTFOLIO.getBroker().getId())
                .assets(List.of(
                        PortfolioDto.AssetSummaryJson.builder()
                                .ticker("USD")
                                .fullName("American Dollar")
                                .avgPurchasePrice(Price.one("USD"))
                                .quantity(Quantity.of(6000))
                                .locked(Quantity.of(0))
                                .free(Quantity.of(6000))
                                .pctProfit(0)
                                .profit(Money.zero("EUR"))
                                .currentPrice(Price.of(0.95, "EUR"))
                                .currentValue(Money.of(5700, "EUR"))
                                .tags(List.of("Cash"))
                                .build(),
                        PortfolioDto.AssetSummaryJson.builder()
                                .ticker("BTC")
                                .fullName("Bitcoin")
                                .avgPurchasePrice(Price.of(40000, "USD"))
                                .quantity(Quantity.of(0.1))
                                .locked(Quantity.of(0))
                                .free(Quantity.of(0.1))
                                .pctProfit(0)
                                .profit(Money.of(0, "EUR"))
                                .currentPrice(Price.of(38000, "EUR"))
                                .currentValue(Money.of(3800, "EUR"))
                                .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                .build()))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(9500, "EUR"))
                .currentValue(Money.of(9500, "EUR"))
                .pctProfit(0)
                .profit(Money.zero("EUR"))
                .build());
    }
}
