package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.AssetNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.quotation.app.QuotationDto;
import com.multi.vidulum.quotation.domain.QuoteNotFoundException;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.trading.domain.IntegrationTest;
import com.multi.vidulum.user.app.UserDto;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static com.multi.vidulum.common.Side.BUY;
import static com.multi.vidulum.common.Side.SELL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class LockingAssetsTests extends IntegrationTest {

    @Test
    void shouldLockCash() {
        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
                .build());
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("BTC")
                .fullName("Bitcoin")
                .segment("Crypto")
                .tags(List.of("Bitcoin", "Crypto", "BTC"))
                .build());

        Awaitility.await().atMost(30, SECONDS).until(() -> {
            try {
                AssetPriceMetadata priceMetadata = quoteRestController.fetch("BINANCE", "USD", "USD");
                log.info("[{}] Loaded price - [{}]", priceMetadata.getSymbol().getId(), priceMetadata.getCurrentPrice());
                return priceMetadata.getSymbol().getId().equals("USD/USD");
            } catch (QuoteNotFoundException e) {
                return false;
            }
        });

        UserDto.UserSummaryJson createdUser = createUser("lu92", "secret", "lu92@email.com");

        activateUser(createdUser.getUserId());

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = registerPortfolio("XYZ", "BINANCE", createdUser.getUserId(), "USD");

        depositMoney(PortfolioId.of(registeredPortfolio.getPortfolioId()), Money.of(100000.0, "USD"));

        // there is no any pending order
        assertThat(orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        TradingDto.OrderSummaryJson placedOrderSummary1 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        List<TradingDto.OrderSummaryJson> allOpenedOrders = orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        assertThat(allOpenedOrders).containsExactlyInAnyOrder(
                TradingDto.OrderSummaryJson.builder()
                        .orderId(placedOrderSummary1.getOrderId())
                        .originOrderId(placedOrderSummary1.getOriginOrderId())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .status(OrderStatus.OPEN)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        // await until portfolio locks [27500 USD]
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            Portfolio portfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId())).get();
            Asset usdAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("USD").equals(asset.getTicker()))
                    .findFirst().orElseThrow(() -> new AssetNotFoundException(Ticker.of("USD")));
            return usdAsset.getLocked().equals(Quantity.of(27500.0)) && usdAsset.getFree().equals(Quantity.of(72500));
        });

        assertThat(portfolioRestController.getAggregatedPortfolio(createdUser.getUserId()))
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUser.getUserId())
                                .segmentedAssets(Map.of("Cash", List.of(
                                        PortfolioDto.AssetSummaryJson.builder()
                                                .ticker("USD")
                                                .fullName("American Dollar")
                                                .avgPurchasePrice(Price.one("USD"))
                                                .quantity(Quantity.of(100000.0))
                                                .locked(Quantity.of(27500.0))
                                                .free(Quantity.of(72500))
                                                .pctProfit(0)
                                                .profit(Money.of(0, "USD"))
                                                .currentPrice(Price.of(1, "USD"))
                                                .currentValue(Money.of(100000.0, "USD"))
                                                .tags(List.of())
                                                .build())))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(0)
                                .build());
    }

    @Test
    void shouldLockAndUnlockCash() {
        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
                .build());
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("BTC")
                .fullName("Bitcoin")
                .segment("Crypto")
                .tags(List.of("Bitcoin", "Crypto", "BTC"))
                .build());

        Awaitility.await().atMost(30, SECONDS).until(() -> {
            try {
                AssetPriceMetadata priceMetadata = quoteRestController.fetch("BINANCE", "USD", "USD");
                log.info("[{}] Loaded price - [{}]", priceMetadata.getSymbol().getId(), priceMetadata.getCurrentPrice());
                return priceMetadata.getSymbol().getId().equals("USD/USD");
            } catch (QuoteNotFoundException e) {
                return false;
            }
        });

        UserDto.UserSummaryJson createdUser = createUser("lu92", "secret", "lu92@email.com");

        activateUser(createdUser.getUserId());

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = registerPortfolio("XYZ", "BINANCE", createdUser.getUserId(), "USD");

        depositMoney(PortfolioId.of(registeredPortfolio.getPortfolioId()), Money.of(100000.0, "USD"));

        // there is no any pending order
        assertThat(orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        TradingDto.OrderSummaryJson placedOrderSummary1 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        List<TradingDto.OrderSummaryJson> allOpenedOrders = orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        assertThat(allOpenedOrders).containsExactlyInAnyOrder(
                TradingDto.OrderSummaryJson.builder()
                        .orderId(placedOrderSummary1.getOrderId())
                        .originOrderId(placedOrderSummary1.getOriginOrderId())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .status(OrderStatus.OPEN)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        TradingDto.OrderSummaryJson cancelOrder = orderRestController.cancelOrder(placedOrderSummary1.getOrderId());
        assertThat(cancelOrder).isEqualTo(
                TradingDto.OrderSummaryJson.builder()
                        .orderId(placedOrderSummary1.getOrderId())
                        .originOrderId(placedOrderSummary1.getOriginOrderId())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .status(OrderStatus.CANCELLED)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        assertThat(orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        // await until portfolio unlocks [27500 USD]
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            Portfolio portfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId())).get();
            Asset usdAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("USD").equals(asset.getTicker()))
                    .findFirst().orElseThrow(() -> new AssetNotFoundException(Ticker.of("USD")));
            return usdAsset.getLocked().isZero() && usdAsset.getFree().equals(Quantity.of(100000.0));
        });

        assertThat(portfolioRestController.getAggregatedPortfolio(createdUser.getUserId()))
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUser.getUserId())
                                .segmentedAssets(Map.of("Cash", List.of(
                                        PortfolioDto.AssetSummaryJson.builder()
                                                .ticker("USD")
                                                .fullName("American Dollar")
                                                .avgPurchasePrice(Price.one("USD"))
                                                .quantity(Quantity.of(100000.0))
                                                .locked(Quantity.zero())
                                                .free(Quantity.of(100000.0))
                                                .pctProfit(0)
                                                .profit(Money.of(0, "USD"))
                                                .currentPrice(Price.of(1, "USD"))
                                                .currentValue(Money.of(100000.0, "USD"))
                                                .tags(List.of())
                                                .build())))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(0)
                                .build());
    }

    @Test
    void shouldExecuteTradeAndLockCashTwoTimesAndUnlockCash() {
        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
                .build());
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("BTC")
                .fullName("Bitcoin")
                .segment("Crypto")
                .tags(List.of("Bitcoin", "Crypto", "BTC"))
                .build());

        Awaitility.await().atMost(30, SECONDS).until(() -> {
            try {
                AssetPriceMetadata priceMetadata = quoteRestController.fetch("BINANCE", "USD", "USD");
                log.info("[{}] Loaded price - [{}]", priceMetadata.getSymbol().getId(), priceMetadata.getCurrentPrice());
                return priceMetadata.getSymbol().getId().equals("USD/USD");
            } catch (QuoteNotFoundException e) {
                return false;
            }
        });

        UserDto.UserSummaryJson createdUser = createUser("lu92", "secret", "lu92@email.com");

        activateUser(createdUser.getUserId());

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = registerPortfolio("XYZ", "BINANCE", createdUser.getUserId(), "USD");

        PortfolioId registeredPortfolioId = PortfolioId.of(registeredPortfolio.getPortfolioId());

        depositMoney(registeredPortfolioId, Money.of(100000.0, "USD"));

        TradingDto.OrderSummaryJson placedBuyOrder1 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin-order-id-1")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(60000, "USD"))
                        .quantity(Quantity.of(1))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(100000),
                Quantity.of(60000),
                Quantity.of(40000));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade1")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .orderId(placedBuyOrder1.getOrderId())
                .userId(createdUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(1))
                .price(Price.of(60000.0, "USD"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(1),
                Quantity.of(0),
                Quantity.of(1));

        TradingDto.OrderSummaryJson placedOrderSummary1 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(50000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(40000),
                Quantity.of(25000),
                Quantity.of(15000));

        TradingDto.OrderSummaryJson placedOrderSummary2 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.OCO)
                        .side(SELL)
                        .targetPrice(Price.of(70000, "USD"))
                        .stopPrice(Price.of(56000, "USD"))
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(1),
                Quantity.of(0.5),
                Quantity.of(0.5));

        List<TradingDto.OrderSummaryJson> allOpenedOrders = orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        assertThat(allOpenedOrders).containsExactlyInAnyOrder(
                TradingDto.OrderSummaryJson.builder()
                        .orderId(placedOrderSummary1.getOrderId())
                        .originOrderId(placedOrderSummary1.getOriginOrderId())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .status(OrderStatus.OPEN)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(50000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build(),
                TradingDto.OrderSummaryJson.builder()
                        .orderId(placedOrderSummary2.getOrderId())
                        .originOrderId(placedOrderSummary2.getOriginOrderId())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.OCO)
                        .side(SELL)
                        .status(OrderStatus.OPEN)
                        .targetPrice(Price.of(70000, "USD"))
                        .stopPrice(Price.of(56000, "USD"))
                        .limitPrice(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );


        // await until portfolio locks [27500 USD] and [0.5 BTC]
        Awaitility.await().atMost(10, SECONDS).until(() -> {
            Portfolio portfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId())).get();
            Asset usdAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("USD").equals(asset.getTicker()))
                    .findFirst().orElseThrow(() -> new AssetNotFoundException(Ticker.of("USD")));

            Asset btcAsset = portfolio.getAssets().stream()
                    .filter(asset -> Ticker.of("BTC").equals(asset.getTicker()))
                    .findFirst().orElseThrow(() -> new AssetNotFoundException(Ticker.of("BTC")));

            return usdAsset.getLocked().equals(Quantity.of(25000)) && btcAsset.getLocked().equals(Quantity.of(0.5));
        });

        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio = portfolioRestController.getAggregatedPortfolio(createdUser.getUserId());
        assertThat(aggregatedPortfolio)
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUser.getUserId())
                                .segmentedAssets(Map.of(
                                        "Cash", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("USD")
                                                        .fullName("American Dollar")
                                                        .avgPurchasePrice(Price.one("USD"))
                                                        .quantity(Quantity.of(40000.0))
                                                        .locked(Quantity.of(25000.0000))
                                                        .free(Quantity.of(15000))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(1, "USD"))
                                                        .currentValue(Money.of(40000.0, "USD"))
                                                        .tags(List.of())
                                                        .build()),
                                        "Crypto", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("BTC")
                                                        .fullName("Bitcoin")
                                                        .avgPurchasePrice(Price.of(60000.0, "USD"))
                                                        .quantity(Quantity.of(1))
                                                        .locked(Quantity.of(0.5))
                                                        .free(Quantity.of(0.5))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(60000.0000, "USD"))
                                                        .currentValue(Money.of(60000.0000, "USD"))
                                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                                        .build())
                                ))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(0)
                                .build());
    }

    @Test
    void shouldExecuteTwoPurchaseTradesAndOneSaleTrade() {
        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
                .build());
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("BTC")
                .fullName("Bitcoin")
                .segment("Crypto")
                .tags(List.of("Bitcoin", "Crypto", "BTC"))
                .build());

        Awaitility.await().atMost(30, SECONDS).until(() -> {
            try {
                AssetPriceMetadata priceMetadata = quoteRestController.fetch("BINANCE", "USD", "USD");
                log.info("[{}] Loaded price - [{}]", priceMetadata.getSymbol().getId(), priceMetadata.getCurrentPrice());
                return priceMetadata.getSymbol().getId().equals("USD/USD");
            } catch (QuoteNotFoundException e) {
                return false;
            }
        });

        UserDto.UserSummaryJson createdUser = createUser("lu92", "secret", "lu92@email.com");

        activateUser(createdUser.getUserId());

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = registerPortfolio("XYZ", "BINANCE", createdUser.getUserId(), "USD");

        PortfolioId registeredPortfolioId = PortfolioId.of(registeredPortfolio.getPortfolioId());

        depositMoney(registeredPortfolioId, Money.of(100000.0, "USD"));

        TradingDto.OrderSummaryJson placedBuyOrder1 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin-order-id-1")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(60000, "USD"))
                        .quantity(Quantity.of(0.4))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(100000),
                Quantity.of(24000),
                Quantity.of(76000));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade1")
                .orderId(placedBuyOrder1.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(createdUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.4))
                .price(Price.of(60000, "USD"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(0.4),
                Quantity.of(0),
                Quantity.of(0.4));

        TradingDto.OrderSummaryJson placedBuyOrder2 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-2")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(60000, "USD"))
                        .quantity(Quantity.of(0.6))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(76000),
                Quantity.of(36000),
                Quantity.of(40000));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade2")
                .orderId(placedBuyOrder2.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(createdUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.6))
                .price(Price.of(60000.0, "USD"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(1),
                Quantity.of(0),
                Quantity.of(1));

        assertThat(portfolioRestController.getAggregatedPortfolio(createdUser.getUserId()))
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUser.getUserId())
                                .segmentedAssets(Map.of(
                                        "Cash", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("USD")
                                                        .fullName("American Dollar")
                                                        .avgPurchasePrice(Price.one("USD"))
                                                        .quantity(Quantity.of(40000.0))
                                                        .locked(Quantity.of(0))
                                                        .free(Quantity.of(40000.0))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(1, "USD"))
                                                        .currentValue(Money.of(40000.0, "USD"))
                                                        .tags(List.of())
                                                        .build()),
                                        "Crypto", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("BTC")
                                                        .fullName("Bitcoin")
                                                        .avgPurchasePrice(Price.of(60000.0, "USD"))
                                                        .quantity(Quantity.of(1))
                                                        .locked(Quantity.zero())
                                                        .free(Quantity.of(1))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(60000.0000, "USD"))
                                                        .currentValue(Money.of(60000.0000, "USD"))
                                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                                        .build())
                                ))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(0)
                                .build());

        TradingDto.OrderSummaryJson placedBuyOrder3 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-3")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(SELL)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(60000, "USD"))
                        .quantity(Quantity.of(0.3, "oz"))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(1),
                Quantity.of(0.3),
                Quantity.of(0.7));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade3")
                .orderId(placedBuyOrder3.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(createdUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(0.3))
                .price(Price.of(60000.0, "USD"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(0.7),
                Quantity.of(0),
                Quantity.of(0.7));

        TradingDto.OrderSummaryJson placedBuyOrder4 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-4")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(SELL)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(60000, "USD"))
                        .quantity(Quantity.of(0.1, "oz"))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(0.7),
                Quantity.of(0.1),
                Quantity.of(0.6));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .orderId(placedBuyOrder4.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(createdUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(0.1))
                .price(Price.of(60000.0, "USD"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(0.6),
                Quantity.of(0),
                Quantity.of(0.6));

        assertThat(portfolioRestController.getAggregatedPortfolio(createdUser.getUserId()))
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUser.getUserId())
                                .segmentedAssets(Map.of(
                                        "Cash", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("USD")
                                                        .fullName("American Dollar")
                                                        .avgPurchasePrice(Price.one("USD"))
                                                        .quantity(Quantity.of(40000.0 + 18000.0 + 6000.0))
                                                        .locked(Quantity.of(0))
                                                        .free(Quantity.of(40000.0 + 18000.0 + 6000.0))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(1, "USD"))
                                                        .currentValue(Money.of(40000.0 + 18000.0 + 6000.0, "USD"))
                                                        .tags(List.of())
                                                        .build()),
                                        "Crypto", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("BTC")
                                                        .fullName("Bitcoin")
                                                        .avgPurchasePrice(Price.of(60000.0, "USD"))
                                                        .quantity(Quantity.of(0.6))
                                                        .locked(Quantity.zero())
                                                        .free(Quantity.of(0.6))
                                                        .pctProfit(0)
                                                        .profit(Money.of(0, "USD"))
                                                        .currentPrice(Price.of(60000.0000, "USD"))
                                                        .currentValue(Money.of(36000.0000, "USD"))
                                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                                        .build())
                                ))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.of(0, "USD"))
                                .pctProfit(0)
                                .build());
    }
}
