package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.AggregatedPortfolio;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioSummaryMapperTest {

    @Mock
    private QuoteRestClient quoteRestClientMock;

    @InjectMocks
    private PortfolioSummaryMapper mapper;

    private static final Portfolio PORTFOLIO;
    private static final AggregatedPortfolio AGGREGATED_PORTFOLIO;
    private static final Broker BROKER = Broker.of("broker");
    private static final Currency USD = Currency.of("USD");
    private static final UserId USER_ID = UserId.of("user");

    static {
        PORTFOLIO = new PortfolioFactory().empty(
                PortfolioId.generate(),
                "XYZ",
                USER_ID,
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

    static {
        AggregatedPortfolio.GroupedAssets cryptoGroupedAssets = AggregatedPortfolio.GroupedAssets.builder().build();
        cryptoGroupedAssets.appendAsset(BROKER, Asset.builder()
                .ticker(Ticker.of("BTC"))
                .avgPurchasePrice(Price.of(40000, "EUR"))
                .quantity(Quantity.of(0.2))
                .locked(Quantity.of(0))
                .free(Quantity.of(0.2))
                .build());
        cryptoGroupedAssets.appendAsset(BROKER, Asset.builder()
                .ticker(Ticker.of("ETH"))
                .avgPurchasePrice(Price.of(2000, "EUR"))
                .quantity(Quantity.of(2))
                .locked(Quantity.of(1))
                .free(Quantity.of(1))
                .build());

        AggregatedPortfolio.GroupedAssets cashGroupedAssets = AggregatedPortfolio.GroupedAssets.builder().build();
        cashGroupedAssets.appendAsset(BROKER, Asset.builder()
                .ticker(Ticker.of("EUR"))
                .avgPurchasePrice(Price.of(1, "EUR"))
                .quantity(Quantity.of(5000))
                .locked(Quantity.of(1000))
                .free(Quantity.of(4000))
                .build());

        AggregatedPortfolio.PortfolioInvestedBalance portfolioInvestedBalance1 = new AggregatedPortfolio.PortfolioInvestedBalance(
                PortfolioId.generate(),
                Currency.of("EUR"),
                Money.of(15000, "EUR"),
                BROKER
        );

        AggregatedPortfolio.PortfolioInvestedBalance portfolioInvestedBalance2 = new AggregatedPortfolio.PortfolioInvestedBalance(
                PortfolioId.generate(),
                Currency.of("PLN"),
                Money.of(20000, "PLN"),
                BROKER
        );

        AGGREGATED_PORTFOLIO = AggregatedPortfolio.builder()
                .userId(USER_ID)
                .segmentedAssets(Map.of(
                        Segment.of("Crypto"), cryptoGroupedAssets,
                        Segment.of("Cash"), cashGroupedAssets
                ))
                .portfolioIds(List.of(portfolioInvestedBalance1.portfolioId(), portfolioInvestedBalance2.portfolioId()))
                .portfolioInvestedBalances(List.of(portfolioInvestedBalance1, portfolioInvestedBalance2))
                .build();
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

    @Test
    public void shouldMapAggregatedPortfolioWithTwoDifferentOriginCurrencies() {

        // When
        when(quoteRestClientMock.fetch(BROKER, Symbol.of("EUR/USD")))
                .thenReturn(AssetPriceMetadata.builder().currentPrice(Price.of(1.05, "USD")).build());
        when(quoteRestClientMock.fetch(BROKER, Symbol.of("BTC/USD")))
                .thenReturn(AssetPriceMetadata.builder().currentPrice(Price.of(38000, "USD")).build());
        when(quoteRestClientMock.fetch(BROKER, Symbol.of("ETH/USD")))
                .thenReturn(AssetPriceMetadata.builder().currentPrice(Price.of(2000, "USD")).build());
        when(quoteRestClientMock.fetch(BROKER, Symbol.of("PLN/USD")))
                .thenReturn(AssetPriceMetadata.builder().currentPrice(Price.of(0.25, "USD")).build());
        when(quoteRestClientMock.fetchBasicInfoAboutAsset(BROKER, Ticker.of("BTC")))
                .thenReturn(AssetBasicInfo.builder()
                        .fullName("Bitcoin")
                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                        .build());
        when(quoteRestClientMock.fetchBasicInfoAboutAsset(BROKER, Ticker.of("ETH")))
                .thenReturn(AssetBasicInfo.builder()
                        .fullName("Ethereum")
                        .tags(List.of("Ethereum", "Crypto", "ETH"))
                        .build());
        when(quoteRestClientMock.fetchBasicInfoAboutAsset(BROKER, Ticker.of("EUR")))
                .thenReturn(AssetBasicInfo.builder()
                        .fullName("Euro")
                        .tags(List.of("Cash"))
                        .build());
        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolioSummary = mapper.map(AGGREGATED_PORTFOLIO, USD);

        // Then
        System.out.println(aggregatedPortfolioSummary);

        assertThat(aggregatedPortfolioSummary).isEqualTo(
                PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                        .userId(USER_ID.getId())
                        .segmentedAssets(
                                Map.of(
                                        "Crypto", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("BTC")
                                                        .fullName("Bitcoin")
                                                        .avgPurchasePrice(Price.of(40000, "EUR"))
                                                        .quantity(Quantity.of(0.2))
                                                        .locked(Quantity.zero())
                                                        .free(Quantity.of(0.2))
                                                        .pctProfit(-0.0952381)
                                                        .profit(Money.of(-800, "USD"))
                                                        .currentPrice(Price.of(38000, "USD"))
                                                        .currentValue(Money.of(7600, "USD"))
                                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                                        .build(),
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("ETH")
                                                        .fullName("Ethereum")
                                                        .avgPurchasePrice(Price.of(2000, "EUR"))
                                                        .quantity(Quantity.of(2.0))
                                                        .locked(Quantity.of(1))
                                                        .free(Quantity.of(1))
                                                        .pctProfit(-0.04761905)
                                                        .profit(Money.of(-200, "USD"))
                                                        .currentPrice(Price.of(2000, "USD"))
                                                        .currentValue(Money.of(4000, "USD"))
                                                        .tags(List.of("Ethereum", "Crypto", "ETH"))
                                                        .build()),
                                        "Cash", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("EUR")
                                                        .fullName("Euro")
                                                        .avgPurchasePrice(Price.one("EUR"))
                                                        .quantity(Quantity.of(5000))
                                                        .locked(Quantity.of(1000))
                                                        .free(Quantity.of(4000))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(1.0500, "USD"))
                                                        .currentValue(Money.of(5250, "USD"))
                                                        .tags(List.of("Cash"))
                                                        .build())
                                ))
                        .portfolioIds(AGGREGATED_PORTFOLIO.getPortfolioIds().stream().map(PortfolioId::getId).collect(Collectors.toList()))
                        .investedBalance(Money.of(20750, "USD"))
                        .currentValue(Money.of(16850, "USD"))
                        .totalProfit(Money.of(-3900, "USD"))
                        .pctProfit(-0.18795181)
                        .build());
    }
}
