package com.multi.vidulum;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.*;
import com.multi.vidulum.pnl.app.PnlDto;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.pnl.domain.PnlPortfolioStatement;
import com.multi.vidulum.pnl.domain.PnlStatement;
import com.multi.vidulum.pnl.domain.PnlTradeDetails;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.quotation.app.QuotationDto;
import com.multi.vidulum.quotation.domain.QuoteNotFoundException;
import com.multi.vidulum.risk_management.app.RiskManagementDto;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.trading.domain.IntegrationTest;
import com.multi.vidulum.user.app.UserDto;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.*;

import static com.multi.vidulum.common.Side.BUY;
import static com.multi.vidulum.common.Side.SELL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class VidulumApplicationTests extends IntegrationTest {

    @Test
    void shouldBuyBitcoinTest() {
        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "BTC", "EUR", 50000, "EUR", 3.2);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);
        quoteRestController.changePrice("BINANCE", "EUR", "EUR", 1, "EUR", 0);
        quoteRestController.changePrice("BINANCE", "USD", "EUR", 0.95, "USD", 0);
        quoteRestController.changePrice("BINANCE", "EUR", "USD", 1.05, "USD", 0);
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

        UserDto.UserSummaryJson createdUserJson = createUser("lu92", "secret", "lu92@email.com");

        Awaitility.await().atMost(5, SECONDS).until(() -> {
            UserId existingUserId = UserId.of(createdUserJson.getUserId());
            return pnlRepository.findByUser(existingUserId).isPresent();
        });

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of())
                .build();

        assertThat(persistedUser).isEqualTo(expectedUserSummary);

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = registerPortfolio("XYZ", "BINANCE", persistedUser.getUserId(), "USD");

        PortfolioId registeredPortfolioId = PortfolioId.of(registeredPortfolio.getPortfolioId());
        depositMoney(registeredPortfolioId, Money.of(100000.0, "USD"));

        TradingDto.OrderSummaryJson placedOrder = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-A")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(60000.0, "USD"))
                        .quantity(Quantity.of(1))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(100000.0), Quantity.of(60000.0), Quantity.of(40000.0));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade1")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .orderId(placedOrder.getOrderId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName("")
                .side(BUY)
                .quantity(Quantity.of(1))
                .price(Price.of(60000.0, "USD"))
                .fee(ZERO_FEE)
                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(1), Quantity.of(0), Quantity.of(1));

        Optional<Portfolio> optionalPortfolio = portfolioRepository.findById(registeredPortfolioId);
        assertThat(optionalPortfolio.isPresent()).isTrue();
        Portfolio portfolio = optionalPortfolio.get();

        Portfolio expectedPortfolio = Portfolio.builder()
                .portfolioId(registeredPortfolioId)
                .userId(UserId.of(persistedUser.getUserId()))
                .name("XYZ")
                .broker(Broker.of("BINANCE"))
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.one("USD"))
                                .quantity(Quantity.of(40000))
                                .locked(Quantity.zero())
                                .free(Quantity.of(40000))
                                .activeLocks(Set.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.of(60000, "USD"))
                                .quantity(Quantity.of(1))
                                .locked(Quantity.zero())
                                .free(Quantity.of(1))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(100000.0, "USD"))
                .allowedDepositCurrency(Currency.of("USD"))
                .build();

        assertThat(portfolio).isEqualTo(expectedPortfolio);
        List<TradingDto.TradeSummaryJson> allTrades = tradeRestController.getAllTrades(createdUserJson.getUserId(), registeredPortfolio.getPortfolioId());
        assertThat(allTrades).hasSize(1);

        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio = portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId(), "USD");

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregatedPortfolio = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(createdUserJson.getUserId())
                .segmentedAssets(Map.of(
                        "Crypto", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("BTC")
                                        .fullName("Bitcoin")
                                        .avgPurchasePrice(Price.of(60000.0, "USD"))
                                        .quantity(Quantity.of(1))
                                        .locked(Quantity.zero())
                                        .free(Quantity.of(1))
                                        .activeLocks(Set.of())
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Price.of(60000.0, "USD"))
                                        .currentValue(Money.of(60000.0, "USD"))
                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                        .build()),
                        "Cash", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("USD")
                                        .fullName("American Dollar")
                                        .avgPurchasePrice(Price.one("USD"))
                                        .quantity(Quantity.of(40000.0))
                                        .locked(Quantity.zero())
                                        .activeLocks(Set.of())
                                        .free(Quantity.of(40000.0))
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Price.of(1, "USD"))
                                        .currentValue(Money.of(40000.0, "USD"))
                                        .tags(List.of())
                                        .build())))
                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                .investedBalance(Money.of(100000.0, "USD"))
                .currentValue(Money.of(100000.0, "USD"))
                .totalProfit(Money.zero("USD"))
                .pctProfit(0)
                .build();

        assertThat(aggregatedPortfolio).isEqualTo(expectedAggregatedPortfolio);
        pnlRestController.makePnlSnapshot(
                PnlDto.MakePnlSnapshotJson.builder()
                        .userId(createdUserJson.getUserId())
                        .from(ZonedDateTime.parse("2021-06-01T00:00:00Z"))
                        .to(ZonedDateTime.parse("2021-06-01T23:59:59Z"))
                        .build());

        PnlDto.PnlHistoryJson pnlHistory = pnlRestController.getPnlHistory(createdUserJson.getUserId());
        System.out.println(pnlHistory);

        TradingDto.OrderSummaryJson placedOrder2 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-B")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.OCO)
                        .side(SELL)
                        .targetPrice(Price.of(80000, "USD"))
                        .stopPrice(Price.of(61000, "USD"))
                        .limitPrice(Price.of(60000, "USD"))
                        .quantity(Quantity.of(0.25))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(1), Quantity.of(0.25), Quantity.of(0.75));

        List<TradingDto.OrderSummaryJson> allOpenedOrders = orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        assertThat(allOpenedOrders)
                .containsExactly(
                        TradingDto.OrderSummaryJson.builder()
                                .orderId(placedOrder2.getOrderId())
                                .originOrderId("origin trade-id-B")
                                .portfolioId(registeredPortfolio.getPortfolioId())
                                .symbol("BTC/USD")
                                .type(OrderType.OCO)
                                .side(SELL)
                                .status(OrderStatus.OPEN)
                                .targetPrice(Price.of(80000, "USD"))
                                .stopPrice(Price.of(61000, "USD"))
                                .limitPrice(Price.of(60000, "USD"))
                                .quantity(Quantity.of(0.25))
                                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                                .build()
                );

        TradingDto.OrderSummaryJson canceledOrder = orderRestController.cancelOrder(placedOrder2.getOrderId());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(1), Quantity.of(0), Quantity.of(1));

        assertThat(List.of(canceledOrder))
                .containsExactly(
                        TradingDto.OrderSummaryJson.builder()
                                .orderId(placedOrder2.getOrderId())
                                .originOrderId("origin trade-id-B")
                                .portfolioId(registeredPortfolio.getPortfolioId())
                                .symbol("BTC/USD")
                                .type(OrderType.OCO)
                                .side(SELL)
                                .status(OrderStatus.CANCELLED)
                                .targetPrice(Price.of(80000, "USD"))
                                .stopPrice(Price.of(61000, "USD"))
                                .limitPrice(Price.of(60000, "USD"))
                                .quantity(Quantity.of(0.25))
                                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                                .build()
                );
        assertThat(orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio1 = portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId(), "USD");
        assertThat(aggregatedPortfolio1)
                .isEqualTo(
                        PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                                .userId(createdUserJson.getUserId())
                                .segmentedAssets(Map.of(
                                        "Crypto", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("BTC")
                                                        .fullName("Bitcoin")
                                                        .avgPurchasePrice(Price.of(60000.0, "USD"))
                                                        .quantity(Quantity.of(1))
                                                        .locked(Quantity.zero())
                                                        .free(Quantity.of(1))
                                                        .activeLocks(Set.of())
                                                        .pctProfit(0)
                                                        .profit(Money.zero("USD"))
                                                        .currentPrice(Price.of(60000.0, "USD"))
                                                        .currentValue(Money.of(60000.0, "USD"))
                                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                                        .build()),
                                        "Cash", List.of(
                                                PortfolioDto.AssetSummaryJson.builder()
                                                        .ticker("USD")
                                                        .fullName("American Dollar")
                                                        .avgPurchasePrice(Price.one("USD"))
                                                        .quantity(Quantity.of(40000.0))
                                                        .locked(Quantity.zero())
                                                        .free(Quantity.of(40000.0))
                                                        .activeLocks(Set.of())
                                                        .pctProfit(0)
                                                        .profit(Money.zero("USD"))
                                                        .currentPrice(Price.of(1, "USD"))
                                                        .currentValue(Money.of(40000.0, "USD"))
                                                        .tags(List.of())
                                                        .build())))
                                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                                .investedBalance(Money.of(100000.0, "USD"))
                                .currentValue(Money.of(100000.0, "USD"))
                                .totalProfit(Money.zero("USD"))
                                .pctProfit(0)
                                .build());

        TradingDto.OrderSummaryJson placedOrder3 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-Y")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .symbol("BTC/USD")
                        .type(OrderType.OCO)
                        .side(SELL)
                        .targetPrice(Price.of(80000, "USD"))
                        .stopPrice(Price.of(61000, "USD"))
                        .limitPrice(Price.of(60000, "USD"))
                        .quantity(Quantity.of(0.25))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(1), Quantity.of(0.25), Quantity.of(0.75));

        TradingDto.OrderExecutionSummaryJson orderExecutionSummaryJson = orderRestController.executeOrder(
                TradingDto.ExecuteOrderJson.builder()
                        .originTradeId("origin trade-id-Y")
                        .originOrderId(placedOrder3.getOriginOrderId())
                        .quantity(Quantity.of(0.25))
                        .price(Price.of(80000, "USD"))
                        .exchangeCurrencyFee(Money.zero("USD"))
                        .transactionFee(Money.zero("USD"))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(0.75), Quantity.of(0), Quantity.of(0.75));

        assertThat(orderExecutionSummaryJson).isEqualTo(
                TradingDto.OrderExecutionSummaryJson.builder()
                        .originTradeId("origin trade-id-Y")
                        .originOrderId("origin order-id-Y")
                        .symbol("BTC/USD")
                        .type(OrderType.OCO)
                        .side(SELL)
                        .quantity(Quantity.of(0.25))
                        .profit(Money.of(4750, "USD"))
                        .build());

        assertThat(orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId())).isEmpty();

        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio2 = portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId(), "USD");

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregatedPortfolio2 = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(createdUserJson.getUserId())
                .segmentedAssets(Map.of(
                        "Crypto", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("BTC")
                                        .fullName("Bitcoin")
                                        .avgPurchasePrice(Price.of(53333.3340, "USD"))
                                        .quantity(Quantity.of(0.75))
                                        .locked(Quantity.zero())
                                        .free(Quantity.of(0.75))
                                        .activeLocks(Set.of())
                                        .pctProfit(0.12499998)
                                        .profit(Money.of(4999.9995, "USD"))
                                        .currentPrice(Price.of(60000.0, "USD"))
                                        .currentValue(Money.of(45000.0, "USD"))
                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                        .build()),
                        "Cash", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("USD")
                                        .fullName("American Dollar")
                                        .avgPurchasePrice(Price.one("USD"))
                                        .quantity(Quantity.of(60000))
                                        .locked(Quantity.zero())
                                        .free(Quantity.of(60000))
                                        .activeLocks(Set.of())
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Price.of(1, "USD"))
                                        .currentValue(Money.of(60000, "USD"))
                                        .tags(List.of())
                                        .build())))
                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                .investedBalance(Money.of(100000.0, "USD"))
                .currentValue(Money.of(105000.0, "USD"))
                .totalProfit(Money.of(5000, "USD"))
                .pctProfit(0.05)
                .build();

        assertThat(aggregatedPortfolio2).isEqualTo(expectedAggregatedPortfolio2);

        PortfolioDto.PortfolioSummaryJson portfolioDenominatedInUSD = portfolioRestController.getPortfolio(registeredPortfolioId.getId(), "USD");
        PortfolioDto.PortfolioSummaryJson portfolioDenominatedInEuro = portfolioRestController.getPortfolio(registeredPortfolioId.getId(), "EUR");
        System.out.println(portfolioDenominatedInEuro);
    }

    @Test
    void buyAndSellImmediatelyBitcoinTest() {
        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
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

        UserDto.UserSummaryJson createdUserJson = createUser("lu92", "secret", "lu92@email.com");

        Awaitility.await().atMost(5, SECONDS).until(() -> {
            UserId existingUserId = UserId.of(createdUserJson.getUserId());
            return pnlRepository.findByUser(existingUserId).isPresent();
        });

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of())
                .build();
        assertThat(persistedUser).isEqualTo(expectedUserSummary);

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = registerPortfolio("XYZ", "BINANCE", persistedUser.getUserId(), "USD");

        PortfolioId registeredPortfolioId = PortfolioId.of(registeredPortfolio.getPortfolioId());
        depositMoney(registeredPortfolioId, Money.of(100000.0, "USD"));

        TradingDto.OrderSummaryJson placedBuyOrder = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-A")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(60000.0, "USD"))
                        .quantity(Quantity.of(1))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(100000), Quantity.of(60000), Quantity.of(40000));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade1")
                .orderId(placedBuyOrder.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(1))
                .fee(ZERO_FEE)
                .price(Price.of(60000.0, "USD"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(1), Quantity.of(0), Quantity.of(1));

        TradingDto.OrderSummaryJson placedSellOrder = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-B")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(SELL)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(80000.0, "USD"))
                        .quantity(Quantity.of(1))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(1), Quantity.of(1), Quantity.of(0));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade2")
                .orderId(placedSellOrder.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(1))
                .price(Price.of(80000, "USD"))
                .fee(ZERO_FEE)
                .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(120000), Quantity.of(0), Quantity.of(120000));

        Optional<Portfolio> optionalPortfolio = portfolioRepository.findById(registeredPortfolioId);
        assertThat(optionalPortfolio.isPresent()).isTrue();
        Portfolio portfolio = optionalPortfolio.get();
        System.out.println(portfolio);

        Portfolio expectedPortfolio = Portfolio.builder()
                .portfolioId(registeredPortfolioId)
                .userId(UserId.of(persistedUser.getUserId()))
                .name("XYZ")
                .broker(Broker.of("BINANCE"))
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.one("USD"))
                                .quantity(Quantity.of(120000))
                                .locked(Quantity.zero())
                                .free(Quantity.of(120000))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(100000.0, "USD"))
                .allowedDepositCurrency(Currency.of("USD"))
                .build();

        assertThat(portfolio).isEqualTo(expectedPortfolio);
        List<TradingDto.TradeSummaryJson> allTrades = tradeRestController.getAllTrades(createdUserJson.getUserId(), registeredPortfolio.getPortfolioId());
        assertThat(allTrades).hasSize(2);

        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio = portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId(), "USD");

        log.info("Aggregated portfolio:\n {}", jsonFormatter.formatToPrettyJson(aggregatedPortfolio));

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregagedPortfolio = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(createdUserJson.getUserId())
                .segmentedAssets(Map.of(
                        "Cash", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("USD")
                                        .fullName("American Dollar")
                                        .avgPurchasePrice(Price.one("USD"))
                                        .quantity(Quantity.of(120000.0))
                                        .locked(Quantity.zero())
                                        .free(Quantity.of(120000.0))
                                        .activeLocks(Set.of())
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Price.of(1, "USD"))
                                        .currentValue(Money.of(120000.0, "USD"))
                                        .tags(List.of())
                                        .build())))
                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                .investedBalance(Money.of(100000.0, "USD"))
                .currentValue(Money.of(120000.0, "USD"))
                .totalProfit(Money.of(20000.0, "USD"))
                .pctProfit(0.2)
                .build();
        assertThat(aggregatedPortfolio).isEqualTo(expectedAggregagedPortfolio);

        PortfolioDto.OpenedPositionsJson openedPositions = portfolioRestController.getOpenedPositions(registeredPortfolio.getPortfolioId());
        assertThat(openedPositions).isEqualTo(PortfolioDto.OpenedPositionsJson.builder()
                .portfolioId(registeredPortfolio.getPortfolioId())
                .positions(List.of())
                .build());
    }

    @Test
    void shouldExecuteTrades() {
        quoteRestController.changePrice("PM", "XAU", "USD", 1800, "USD", 0);
        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "ETH", "USD", 2850, "USD", 1.09);
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
        quoteRestController.registerAssetBasicInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("ETH")
                .fullName("Ethereum")
                .segment("Crypto")
                .tags(List.of("Ethereum", "Crypto", "ETH"))
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

        UserDto.UserSummaryJson createdUserJson = createUser("lu92", "secret", "lu92@email.com");

        Awaitility.await().atMost(5, SECONDS).until(() -> {
            UserId existingUserId = UserId.of(createdUserJson.getUserId());
            return pnlRepository.findByUser(existingUserId).isPresent();
        });

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of())
                .build();
        assertThat(persistedUser).isEqualTo(expectedUserSummary);

        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = registerPortfolio("XYZ", "BINANCE", persistedUser.getUserId(), "USD");

        PortfolioId registeredPortfolioId = PortfolioId.of(registeredPortfolio.getPortfolioId());

        depositMoney(registeredPortfolioId, Money.of(100000.0, "USD"));

        TradingDto.OrderSummaryJson placedOrderSummary1 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-1")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(60000, "USD"))
                        .quantity(Quantity.of(0.1))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(100000.0),
                Quantity.of(6000),
                Quantity.of(94000));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade1")
                .orderId(placedOrderSummary1.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.1))
                .price(Price.of(60000.0, "USD"))
                .fee(ZERO_FEE)
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(0.1),
                Quantity.of(0),
                Quantity.of(0.1));

        TradingDto.OrderSummaryJson placedOrderSummary2 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-2")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(30000, "USD"))
                        .quantity(Quantity.of(0.1))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(94000),
                Quantity.of(3000),
                Quantity.of(91000));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade2")
                .orderId(placedOrderSummary2.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.1))
                .price(Price.of(30000, "USD"))
                .fee(ZERO_FEE)
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(0.2),
                Quantity.of(0),
                Quantity.of(0.2));

        TradingDto.OrderSummaryJson placedOrderSummary3 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-3")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(30000, "USD"))
                        .quantity(Quantity.of(0.2))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(91000),
                Quantity.of(6000),
                Quantity.of(85000));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade3")
                .orderId(placedOrderSummary3.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.2))
                .price(Price.of(30000, "USD"))
                .fee(ZERO_FEE)
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(0.4),
                Quantity.of(0),
                Quantity.of(0.4));

        TradingDto.OrderSummaryJson placedOrderSummary4 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-4")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("BTC/USD")
                        .type(OrderType.LIMIT)
                        .side(SELL)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(30000, "USD"))
                        .quantity(Quantity.of(0.15))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(0.4),
                Quantity.of(0.15),
                Quantity.of(0.25));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade4")
                .orderId(placedOrderSummary4.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("BTC/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(0.15))
                .price(Price.of(40000, "USD"))
                .fee(ZERO_FEE)
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("BTC"),
                Quantity.of(0.25),
                Quantity.of(0),
                Quantity.of(0.25));

        TradingDto.OrderSummaryJson placedOrderSummary5 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-5")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("ETH/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(2800, "USD"))
                        .quantity(Quantity.of(0.75))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(91000),
                Quantity.of(2100),
                Quantity.of(88900));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade5")
                .orderId(placedOrderSummary5.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.75))
                .price(Price.of(2800, "USD"))
                .fee(ZERO_FEE)
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("ETH"),
                Quantity.of(0.75),
                Quantity.of(0),
                Quantity.of(0.75));

        TradingDto.OrderSummaryJson placedOrderSummary6 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-6")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("ETH/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(2800, "USD"))
                        .quantity(Quantity.of(0.25))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(88900),
                Quantity.of(700),
                Quantity.of(88200));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade6")
                .orderId(placedOrderSummary6.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.25))
                .price(Price.of(2800, "USD"))
                .fee(ZERO_FEE)
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("ETH"),
                Quantity.of(1),
                Quantity.of(0),
                Quantity.of(1));

        TradingDto.OrderSummaryJson placedOrderSummary7 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-7")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("ETH/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(3400, "USD"))
                        .quantity(Quantity.of(0.5))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(88200),
                Quantity.of(1700),
                Quantity.of(86500));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade7")
                .orderId(placedOrderSummary7.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(BUY)
                .quantity(Quantity.of(0.5))
                .price(Price.of(3400, "USD"))
                .fee(ZERO_FEE)
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("ETH"),
                Quantity.of(1.5),
                Quantity.of(0),
                Quantity.of(1.5));

        TradingDto.OrderSummaryJson placedOrderSummary8 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin order-id-8")
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("ETH/USD")
                        .type(OrderType.LIMIT)
                        .side(SELL)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(3000, "USD"))
                        .quantity(Quantity.of(0.2))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("ETH"),
                Quantity.of(1.5),
                Quantity.of(0.2),
                Quantity.of(1.3));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade8")
                .orderId(placedOrderSummary8.getOrderId())
                .portfolioId(registeredPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("ETH/USD")
                .subName(SubName.none().getName())
                .side(SELL)
                .quantity(Quantity.of(0.2))
                .price(Price.of(3000, "USD"))
                .fee(ZERO_FEE)
                .build());

        awaitUntilAssetMetadataIsEqualTo(
                registeredPortfolioId, Ticker.of("ETH"),
                Quantity.of(1.3),
                Quantity.of(0),
                Quantity.of(1.3));

        Optional<Portfolio> optionalPortfolio = portfolioRepository.findById(PortfolioId.of(registeredPortfolio.getPortfolioId()));
        assertThat(optionalPortfolio.isPresent()).isTrue();
        Portfolio portfolio = optionalPortfolio.get();

        Portfolio expectedPortfolio = Portfolio.builder()
                .portfolioId(PortfolioId.of(registeredPortfolio.getPortfolioId()))
                .userId(UserId.of(persistedUser.getUserId()))
                .name("XYZ")
                .broker(Broker.of("BINANCE"))
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.one("USD"))
                                .quantity(Quantity.of(87100))
                                .locked(Quantity.zero())
                                .free(Quantity.of(87100.0))
                                .activeLocks(Set.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.of(36000, "USD"))
                                .quantity(Quantity.of(0.25))
                                .locked(Quantity.zero())
                                .free(Quantity.of(0.25))
                                .activeLocks(Set.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("ETH"))
                                .subName(SubName.none())
                                .avgPurchasePrice(Price.of(3000, "USD"))
                                .quantity(Quantity.of(1.3))
                                .locked(Quantity.zero())
                                .free(Quantity.of(1.3))
                                .activeLocks(Set.of())
                                .build()
                ))
                .status(PortfolioStatus.OPEN)
                .investedBalance(Money.of(100000.0, "USD"))
                .allowedDepositCurrency(Currency.of("USD"))
                .build();

        assertThat(portfolio).isEqualTo(expectedPortfolio);
        List<TradingDto.TradeSummaryJson> allTrades = tradeRestController.getAllTrades(createdUserJson.getUserId(), registeredPortfolio.getPortfolioId());
        assertThat(allTrades).hasSize(8);


        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolio = portfolioRestController.getAggregatedPortfolio(createdUserJson.getUserId(), "USD");
        log.info("Aggregated portfolio:\n{}", jsonFormatter.formatToPrettyJson(aggregatedPortfolio));

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregatedPortfolio = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(createdUserJson.getUserId())
                .segmentedAssets(Map.of("Cash", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("USD")
                                        .fullName("American Dollar")
                                        .avgPurchasePrice(Price.one("USD"))
                                        .quantity(Quantity.of(87100))
                                        .locked(Quantity.zero())
                                        .free(Quantity.of(87100))
                                        .activeLocks(Set.of())
                                        .pctProfit(0)
                                        .profit(Money.of(0, "USD"))
                                        .currentPrice(Price.of(1, "USD"))
                                        .currentValue(Money.of(87100.0, "USD"))
                                        .tags(List.of())
                                        .build()),
                        "Crypto", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("ETH")
                                        .fullName("Ethereum")
                                        .avgPurchasePrice(Price.of(3000.0000, "USD"))
                                        .quantity(Quantity.of(1.3))
                                        .locked(Quantity.zero())
                                        .free(Quantity.of(1.3))
                                        .activeLocks(Set.of())
                                        .pctProfit(-0.05)
                                        .profit(Money.of(-195.0000, "USD"))
                                        .currentPrice(Price.of(2850.0000, "USD"))
                                        .currentValue(Money.of(3705.0000, "USD"))
                                        .tags(List.of("Ethereum", "Crypto", "ETH"))
                                        .build(),
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("BTC")
                                        .fullName("Bitcoin")
                                        .avgPurchasePrice(Price.of(36000.0000, "USD"))
                                        .quantity(Quantity.of(0.25))
                                        .locked(Quantity.zero())
                                        .free(Quantity.of(0.25))
                                        .activeLocks(Set.of())
                                        .pctProfit(0.66666666)
                                        .profit(Money.of(6000.0, "USD"))
                                        .currentPrice(Price.of(60000.0000, "USD"))
                                        .currentValue(Money.of(15000.0000, "USD"))
                                        .tags(List.of("Bitcoin", "Crypto", "BTC"))
                                        .build())
                ))
                .portfolioIds(List.of(registeredPortfolio.getPortfolioId()))
                .investedBalance(Money.of(100000.0, "USD"))
                .currentValue(Money.of(105805, "USD"))
                .totalProfit(Money.of(5805.0, "USD"))
                .pctProfit(0.05805)
                .build();

        assertThat(expectedAggregatedPortfolio).isEqualTo(aggregatedPortfolio);

        placeOrder(TradingDto.PlaceOrderJson.builder()
                .originOrderId("origin trade-id-1")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .symbol("ETH/USD")
                .type(OrderType.OCO)
                .side(SELL)
                .targetPrice(Price.of(3500, "USD"))
                .stopPrice(Price.of(3000, "USD"))
                .limitPrice(Price.of(2700, "USD"))
                .quantity(Quantity.of(0.5))
                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                .build());

        placeOrder(TradingDto.PlaceOrderJson.builder()
                .originOrderId("origin trade-id-2")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .symbol("BTC/USD")
                .type(OrderType.OCO)
                .side(SELL)
                .targetPrice(Price.of(70000, "USD"))
                .stopPrice(Price.of(60000, "USD"))
                .limitPrice(Price.of(55000, "USD"))
                .quantity(Quantity.of(0.1))
                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                .build());

        placeOrder(TradingDto.PlaceOrderJson.builder()
                .originOrderId("origin trade-id-3")
                .portfolioId(registeredPortfolio.getPortfolioId())
                .symbol("ETH/USD")
                .type(OrderType.OCO)
                .side(SELL)
                .targetPrice(Price.of(4000, "USD"))
                .stopPrice(Price.of(3000, "USD"))
                .limitPrice(Price.of(2900, "USD"))
                .quantity(Quantity.of(0.5))
                .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                .build());


        PortfolioDto.OpenedPositionsJson openedPositions = portfolioRestController.getOpenedPositions(registeredPortfolio.getPortfolioId());

        assertThat(openedPositions.getPortfolioId()).isEqualTo(registeredPortfolio.getPortfolioId());
        assertThat(openedPositions.getPositions()).containsExactlyInAnyOrder(
                PortfolioDto.PositionSummaryJson.builder()
                        .symbol("BTC/USD")
                        .targetPrice(Price.of(70000, "USD"))
                        .entryPrice(Price.of(60000, "USD"))
                        .stopLoss(Price.of(55000, "USD"))
                        .quantity(Quantity.of(0.1))
                        .risk(Money.of(500, "USD"))
                        .reward(Money.of(1000, "USD"))
                        .riskRewardRatio(RiskRewardRatio.of(1, 2))
                        .value(Money.of(0.1 * 60000, "USD"))
                        .pctProfit(0)
                        .build(),
                PortfolioDto.PositionSummaryJson.builder()
                        .symbol("ETH/USD")
                        .targetPrice(Price.of(3750, "USD"))
                        .entryPrice(Price.of(3000, "USD"))
                        .stopLoss(Price.of(2800, "USD"))
                        .quantity(Quantity.of(1))
                        .risk(Money.of(200, "USD"))
                        .reward(Money.of(750, "USD"))
                        .riskRewardRatio(RiskRewardRatio.of(1, 3.75))
                        .value(Money.of(2850, "USD"))
                        .pctProfit(-0.05)
                        .build()
        );


        List<TradingDto.OrderSummaryJson> allOpenedOrders = orderRestController.getAllOpenedOrders(registeredPortfolio.getPortfolioId());
        log.info("[{}]", allOpenedOrders);

        RiskManagementDto.RiskManagementStatementJson riskManagementStatement = riskManagementRestController.getRiskManagementStatement(registeredPortfolio.getPortfolioId());
        System.out.println(jsonFormatter.formatToPrettyJson(riskManagementStatement));

//        assertThat(riskManagementStatement.getAssetRiskManagementStatements()).containsExactlyInAnyOrder(
//
//                RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                        .ticker("USD")
//                        .quantity(Quantity.of(88100))
//                        .stopLosses(List.of())
//                        .avgPurchasePrice(Money.of(1, "USD"))
//                        .currentValue(Money.of(88100, "USD"))
//                        .currentPrice(Money.of(1, "USD"))
//                        .safeMoney(Money.of(88100, "USD"))
//                        .riskMoney(Money.zero("USD"))
//                        .ragStatus(RagStatus.GREEN)
//                        .pctRiskOfPortfolio(0)
//                        .build(),
//                RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                        .ticker("BTC")
//                        .quantity(Quantity.of(0.20000000000000004))
//                        .stopLosses(List.of(
//                                RiskManagementDto.StopLossJson.builder()
//                                        .symbol("BTC/USD")
//                                        .quantity(Quantity.of(0.1))
//                                        .price(Money.of(55000, "USD"))
//                                        .isApplicable(true)
//                                        .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                        .build()
//                        ))
//                        .avgPurchasePrice(Money.of(40000, "USD"))
//                        .currentValue(Money.of(12000, "USD"))
//                        .currentPrice(Money.of(60000, "USD"))
//                        .safeMoney(Money.of(5499.999999999998200000000, "USD"))
//                        .riskMoney(Money.of(6500.000000000001800000000, "USD"))
//                        .ragStatus(RagStatus.GREEN)
//                        .pctRiskOfPortfolio(14.96999999)
//                        .build(),
//                RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                        .ticker("ETH")
//                        .quantity(Quantity.of(1.3))
//                        .stopLosses(List.of(
//                                RiskManagementDto.StopLossJson.builder()
//                                        .symbol("ETH/USD")
//                                        .quantity(Quantity.of(0.5))
//                                        .price(Money.of(2700, "USD"))
//                                        .isApplicable(true)
//                                        .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                        .build(),
//                                RiskManagementDto.StopLossJson.builder()
//                                        .symbol("ETH/USD")
//                                        .quantity(Quantity.of(0.5))
//                                        .price(Money.of(2900, "USD"))
//                                        .isApplicable(false)
//                                        .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                        .build()
//                        ))
//                        .avgPurchasePrice(Money.of(3000, "USD"))
//                        .currentValue(Money.of(3705, "USD"))
//                        .currentPrice(Money.of(2850, "USD"))
//                        .safeMoney(Money.of(1350, "USD"))
//                        .riskMoney(Money.of(2355,"USD"))
//                        .ragStatus(RagStatus.GREEN)
//                        .pctRiskOfPortfolio(43.07855626)
//                        .build()
//        );
//
//        assertThat(riskManagementStatement).isEqualTo(
//                RiskManagementDto.RiskManagementStatementJson.builder()
//                        .portfolioId(registeredPortfolio.getPortfolioId())
//                        .userId(registeredPortfolio.getUserId())
//                        .description(registeredPortfolio.getName())
//                        .broker(registeredPortfolio.getBroker())
//                        .assetRiskManagementStatements(
//                                List.of(
//                                        RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                                                .ticker("USD")
//                                                .quantity(Quantity.of(88100))
//                                                .stopLosses(List.of())
//                                                .avgPurchasePrice(Money.of(1, "USD"))
//                                                .currentValue(Money.of(88100, "USD"))
//                                                .currentPrice(Money.of(1, "USD"))
//                                                .safeMoney(Money.of(88100, "USD"))
//                                                .riskMoney(Money.zero("USD"))
//                                                .ragStatus(RagStatus.GREEN)
//                                                .pctRiskOfPortfolio(0)
//                                                .build(),
//                                        RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                                                .ticker("BTC")
//                                                .quantity(Quantity.of(0.20000000000000004))
//                                                .stopLosses(List.of(
//                                                        RiskManagementDto.StopLossJson.builder()
//                                                                .symbol("BTC/USD")
//                                                                .quantity(Quantity.of(0.1))
//                                                                .price(Money.of(55000, "USD"))
//                                                                .isApplicable(true)
//                                                                .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                                                .build()
//                                                ))
//                                                .avgPurchasePrice(Money.of(40000, "USD"))
//                                                .currentValue(Money.of(12000, "USD"))
//                                                .currentPrice(Money.of(60000, "USD"))
//                                                .safeMoney(Money.of(5499.999999999998200000000, "USD"))
//                                                .riskMoney(Money.of(6500.000000000001800000000, "USD"))
//                                                .ragStatus(RagStatus.GREEN)
//                                                .pctRiskOfPortfolio(14.96999999)
//                                                .build(),
//                                        RiskManagementDto.AssetRiskManagementStatementJson.builder()
//                                                .ticker("ETH")
//                                                .quantity(Quantity.of(1.3))
//                                                .stopLosses(List.of(
//                                                        RiskManagementDto.StopLossJson.builder()
//                                                                .symbol("ETH/USD")
//                                                                .quantity(Quantity.of(0.5))
//                                                                .price(Money.of(2700, "USD"))
//                                                                .isApplicable(true)
//                                                                .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                                                .build(),
//                                                        RiskManagementDto.StopLossJson.builder()
//                                                                .symbol("ETH/USD")
//                                                                .quantity(Quantity.of(0.5))
//                                                                .price(Money.of(2900, "USD"))
//                                                                .isApplicable(false)
//                                                                .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
//                                                                .build()
//                                                ))
//                                                .avgPurchasePrice(Money.of(3000, "USD"))
//                                                .currentValue(Money.of(3705, "USD"))
//                                                .currentPrice(Money.of(2850, "USD"))
//                                                .safeMoney(Money.of(1350, "USD"))
//                                                .riskMoney(Money.of(2355,"USD"))
//                                                .ragStatus(RagStatus.GREEN)
//                                                .pctRiskOfPortfolio(43.07855626)
//                                                .build()
//                                ))
//                        .investedBalance(portfolio.getInvestedBalance())
//                        .currentValue(Money.of(103805, "USD"))
//                        .profit(Money.of(3805, "USD"))
//                        .safe(Money.of(94949.999999999998200000000, "USD"))
//                        .risk(Money.of(8855.000000000001800000000, "USD"))
//                        .pctProfit(-0.96195)
//                        .riskPct(-12.72275551)
//                        .build()
//        );

    }

    @Test
    void shouldPersistPortfolioForPreciousMetals() {
        quoteRestController.changePrice("PM", "XAU", "USD", 1800, "USD", 0);
        quoteRestController.changePrice("PM", "XAG", "USD", 95, "USD", 0);
        quoteRestController.changePrice("PM", "USD", "USD", 1, "USD", 0);
        quoteRestController.registerAssetBasicInfo("PM", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("XAU")
                .fullName("Gold")
                .segment("Precious Metals")
                .tags(List.of("Gold", "Precious Metals"))
                .build());
        quoteRestController.registerAssetBasicInfo("PM", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("XAG")
                .fullName("Silver")
                .segment("Precious Metals")
                .tags(List.of("Silver", "Precious Metals"))
                .build());
        quoteRestController.registerAssetBasicInfo("PM", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
                .build());

        Awaitility.await().atMost(30, SECONDS).until(() -> {
            try {
                AssetPriceMetadata priceMetadata = quoteRestController.fetch("PM", "USD", "USD");
                log.info("[{}] Loaded price - [{}]", priceMetadata.getSymbol().getId(), priceMetadata.getCurrentPrice());
                return priceMetadata.getSymbol().getId().equals("USD/USD");
            } catch (QuoteNotFoundException e) {
                return false;
            }
        });

        UserDto.UserSummaryJson createdUserJson = createUser("lu92", "secret", "lu92@email.com");

        Awaitility.await().atMost(5, SECONDS).until(() -> {
            UserId existingUserId = UserId.of(createdUserJson.getUserId());
            return pnlRepository.findByUser(existingUserId).isPresent();
        });

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of())
                .build();

        assertThat(persistedUser).isEqualTo(expectedUserSummary);

        UserDto.PortfolioRegistrationSummaryJson registeredPreciousMetalsPortfolio = registerPortfolio("Precious Metals 1", "PM", persistedUser.getUserId(), "USD");

        UserDto.PortfolioRegistrationSummaryJson registeredPreciousMetalsPortfolio2 = registerPortfolio("Precious Metals 2", "PM", persistedUser.getUserId(), "USD");

        PortfolioId registeredPortfolioId = PortfolioId.of(registeredPreciousMetalsPortfolio.getPortfolioId());
        PortfolioId registeredPortfolioId2 = PortfolioId.of(registeredPreciousMetalsPortfolio2.getPortfolioId());

        depositMoney(registeredPortfolioId, Money.of(10000, "USD"));

        depositMoney(registeredPortfolioId2, Money.of(10000, "USD"));

        TradingDto.OrderSummaryJson placedBuyOrder1 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-1")
                        .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                        .broker(registeredPreciousMetalsPortfolio.getBroker())
                        .symbol("XAU/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(1800, "USD"))
                        .quantity(Quantity.of(2, "oz"))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(10000), Quantity.of(3600), Quantity.of(6400));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("pm-trade1")
                .orderId(placedBuyOrder1.getOrderId())
                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("XAU/USD")
                .subName("Maple Leaf")
                .side(BUY)
                .quantity(Quantity.of(2, "oz"))
                .price(Price.of(1800, "USD"))
                .fee(ZERO_FEE)
                .originDateTime(ZonedDateTime.parse("2021-02-01T06:24:11Z"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("XAU"),
                Quantity.of(2, "oz"), Quantity.of(0), Quantity.of(2, "oz")); // TODO: change locked's unit to 'oz'

        TradingDto.OrderSummaryJson placedBuyOrder2 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-2")
                        .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                        .broker(registeredPreciousMetalsPortfolio.getBroker())
                        .symbol("XAU/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(1820, "USD"))
                        .quantity(Quantity.of(2, "oz"))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(6400), Quantity.of(3640), Quantity.of(2760));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("pm-trade2")
                .orderId(placedBuyOrder2.getOrderId())
                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("XAU/USD")
                .subName("Krugerrand")
                .side(BUY)
                .quantity(Quantity.of(2, "oz"))
                .price(Price.of(1820, "USD"))
                .fee(ZERO_FEE)
                .originDateTime(ZonedDateTime.parse("2021-03-02T12:14:11Z"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("XAU"),
                Quantity.of(4, "oz"), Quantity.of(0), Quantity.of(4, "oz")); // TODO: change locked's unit to 'oz'

        TradingDto.OrderSummaryJson placedBuyOrder3 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-3")
                        .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                        .broker(registeredPreciousMetalsPortfolio.getBroker())
                        .symbol("XAU/USD")
                        .type(OrderType.LIMIT)
                        .side(SELL)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(1850, "USD"))
                        .quantity(Quantity.of(1, "oz"))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("XAU"),
                Quantity.of(4, "oz"), Quantity.of(1), Quantity.of(3, "oz")); // TODO: change locked's unit to 'oz'

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("pm-trade3")
                .orderId(placedBuyOrder3.getOrderId())
                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("XAU/USD")
                .subName("Maple Leaf")
                .side(SELL)
                .quantity(Quantity.of(1, "oz"))
                .price(Price.of(1850, "USD"))
                .fee(ZERO_FEE)
                .originDateTime(ZonedDateTime.parse("2021-04-01T16:24:11Z"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(4610), Quantity.of(0), Quantity.of(4610));

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("XAU"),
                Quantity.of(3, "oz"), Quantity.of(0), Quantity.of(3, "oz")); // TODO: change locked's unit to 'oz'

        TradingDto.OrderSummaryJson placedBuyOrder4 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-4")
                        .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                        .broker(registeredPreciousMetalsPortfolio.getBroker())
                        .symbol("XAG/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(90, "USD"))
                        .quantity(Quantity.of(5, "oz"))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("USD"),
                Quantity.of(4610), Quantity.of(450), Quantity.of(4160));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("pm-trade4")
                .orderId(placedBuyOrder4.getOrderId())
                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("XAG/USD")
                .subName("Maple Leaf")
                .side(BUY)
                .quantity(Quantity.of(5, "oz"))
                .price(Price.of(90, "USD"))
                .fee(ZERO_FEE)
                .originDateTime(ZonedDateTime.parse("2021-03-02T12:14:11Z"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId, Ticker.of("XAG"),
                Quantity.of(5, "oz"), Quantity.of(0), Quantity.of(5, "oz")); // TODO: change locked's unit to 'oz'

        TradingDto.OrderSummaryJson placedBuyOrder5 = placeOrder(
                TradingDto.PlaceOrderJson.builder()
                        .originOrderId("origin trade-id-5")
                        .portfolioId(registeredPreciousMetalsPortfolio2.getPortfolioId())
                        .broker(registeredPreciousMetalsPortfolio2.getBroker())
                        .symbol("XAU/USD")
                        .type(OrderType.LIMIT)
                        .side(BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(2000, "USD"))
                        .quantity(Quantity.of(1, "oz"))
                        .originDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId2, Ticker.of("USD"),
                Quantity.of(10000), Quantity.of(2000), Quantity.of(8000));

        makeTrade(TradingDto.TradeExecutedJson.builder()
                .originTradeId("pm-trade5")
                .orderId(placedBuyOrder5.getOrderId())
                .portfolioId(registeredPreciousMetalsPortfolio2.getPortfolioId())
                .userId(persistedUser.getUserId())
                .symbol("XAU/USD")
                .subName("Maple Leaf")
                .side(BUY)
                .quantity(Quantity.of(1, "oz"))
                .price(Price.of(2000, "USD"))
                .fee(ZERO_FEE)
                .originDateTime(ZonedDateTime.parse("2021-02-01T06:24:11Z"))
                .build());

        awaitUntilAssetMetadataIsEqualTo(registeredPortfolioId2, Ticker.of("XAU"),
                Quantity.of(1, "oz"), Quantity.of(0), Quantity.of(1, "oz")); // TODO: change locked's unit to 'oz'

        List<TradingDto.TradeSummaryJson> allTrades = tradeRestController.getAllTrades(createdUserJson.getUserId(), registeredPreciousMetalsPortfolio.getPortfolioId());
        assertThat(allTrades).hasSize(4);

        List<TradingDto.TradeSummaryJson> lastTwoTrades = tradeRestController.getTradesInDateRange(
                createdUserJson.getUserId(),
                ZonedDateTime.parse("2021-03-01T00:00:00Z"),
                ZonedDateTime.parse("2021-05-01T00:00:00Z"));
        assertThat(lastTwoTrades).hasSize(3);

        PortfolioDto.AggregatedPortfolioSummaryJson aggregatedPortfolioJson = portfolioRestController.getAggregatedPortfolio(registeredPreciousMetalsPortfolio.getUserId(), "USD");

        log.info("Aggregated Portfolio: {}", jsonFormatter.formatToPrettyJson(aggregatedPortfolioJson));

        PortfolioDto.AggregatedPortfolioSummaryJson expectedAggregatedPortfolio = PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(registeredPreciousMetalsPortfolio.getUserId())
                .segmentedAssets(Map.of(
                        "Cash", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("USD")
                                        .fullName("American Dollar")
                                        .avgPurchasePrice(Price.one("USD"))
                                        .quantity(Quantity.of(12160))
                                        .locked(Quantity.zero())
                                        .free(Quantity.of(12160))
                                        .activeLocks(Set.of())
                                        .pctProfit(0)
                                        .profit(Money.zero("USD"))
                                        .currentPrice(Price.of(1, "USD"))
                                        .currentValue(Money.of(12160, "USD"))
                                        .tags(List.of())
                                        .build()),
                        "Precious Metals", List.of(
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("XAG")
                                        .fullName("Silver")
                                        .avgPurchasePrice(Price.of(90, "USD"))
                                        .quantity(Quantity.of(5, "oz"))
                                        .locked(Quantity.of(0, "oz"))
                                        .free(Quantity.of(5, "oz"))
                                        .activeLocks(Set.of())
                                        .pctProfit(0.05555555)
                                        .profit(Money.of(25, "USD"))
                                        .currentPrice(Price.of(95, "USD"))
                                        .currentValue(Money.of(475, "USD"))
                                        .tags(List.of("Silver", "Precious Metals"))
                                        .build(),
                                PortfolioDto.AssetSummaryJson.builder()
                                        .ticker("XAU")
                                        .fullName("Gold")
                                        .avgPurchasePrice(Price.of(1847.5003, "USD"))
                                        .quantity(Quantity.of(4, "oz"))
                                        .locked(Quantity.of(0, "oz"))
                                        .free(Quantity.of(4, "oz"))
                                        .activeLocks(Set.of())
                                        .pctProfit(-0.02571056)
                                        .profit(Money.of(-190.0010, "USD"))
                                        .currentPrice(Price.of(1800, "USD"))
                                        .currentValue(Money.of(7200, "USD"))
                                        .tags(List.of("Gold", "Precious Metals"))
                                        .build())))
                .portfolioIds(List.of(registeredPreciousMetalsPortfolio.getPortfolioId(), registeredPreciousMetalsPortfolio2.getPortfolioId()))
                .investedBalance(Money.of(20000, "USD"))
                .currentValue(Money.of(19835, "USD"))
                .totalProfit(Money.of(-165, "USD"))
                .pctProfit(-0.00825)
                .build();

        assertThat(expectedAggregatedPortfolio).isEqualTo(aggregatedPortfolioJson);

        pnlRestController.makePnlSnapshot(
                PnlDto.MakePnlSnapshotJson.builder()
                        .userId(createdUserJson.getUserId())
                        .from(ZonedDateTime.parse("2021-02-01T00:00:00Z"))
                        .to(ZonedDateTime.parse("2021-06-01T00:00:00Z"))
                        .build());

        PnlDto.PnlHistoryJson pnlHistoryJson = pnlRestController.getPnlHistory(createdUserJson.getUserId());
        System.out.println(pnlHistoryJson);

        System.out.println(jsonFormatter.formatToPrettyJson(pnlHistoryJson));
        assertThat(pnlHistoryJson.getUserId()).isEqualTo(createdUserJson.getUserId());
        assertThat(pnlHistoryJson.getPnlStatements()).hasSize(1);
        assertThat(pnlHistoryJson.getPnlStatements())
                .usingElementComparatorIgnoringFields("dateTime", "portfolioStatements")
                .isEqualTo(List.of(
                        PnlDto.PnlStatementJson.builder()
                                .investedBalance(Money.of(20000, "USD"))
                                .currentValue(Money.of(19835, "USD"))
                                .totalProfit(Money.of(-165, "USD"))
                                .pctProfit(-0.00825)
                                .build()));

        PnlDto.PnlStatementJson pnlStatementJson = pnlHistoryJson.getPnlStatements().get(0);
        assertThat(pnlStatementJson.getPortfolioStatements()).hasSize(2);
        assertThat(pnlStatementJson.getPortfolioStatements())
                .usingElementComparatorIgnoringFields("executedTrades")
                .isEqualTo(List.of(
                        PnlDto.PnlPortfolioStatementJson.builder()
                                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                                .investedBalance(Money.of(10000, "USD"))
                                .currentValue(Money.of(10035, "USD"))
                                .totalProfit(Money.of(35, "USD"))
                                .pctProfit(-0.9965)
                                .build(),
                        PnlDto.PnlPortfolioStatementJson.builder()
                                .portfolioId(registeredPreciousMetalsPortfolio2.getPortfolioId())
                                .investedBalance(Money.of(10000, "USD"))
                                .currentValue(Money.of(9800, "USD"))
                                .totalProfit(Money.of(-200, "USD"))
                                .pctProfit(-1.02)
                                .build()
                ));

        assertThat(pnlStatementJson.getPortfolioStatements().get(0).getExecutedTrades()).hasSize(4);

        // find portfolio-statement for first portfolio
        PnlDto.PnlPortfolioStatementJson statementOfFirstPortfolio = pnlHistoryJson.getPnlStatements().get(0).getPortfolioStatements().stream()
                .filter(pnlPortfolioStatementJson -> pnlPortfolioStatementJson.getPortfolioId().equals(registeredPreciousMetalsPortfolio.getPortfolioId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("portfolio-statement is missing"));

        assertThat(statementOfFirstPortfolio.getExecutedTrades())
                .usingElementComparatorIgnoringFields("tradeId")
                .isEqualTo(List.of(
                        PnlDto.PnlTradeDetailsJson.builder()
                                .originTradeId("pm-trade1")
                                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                                .symbol("XAU/USD")
                                .subName("Maple Leaf")
                                .side(BUY)
                                .quantity(Quantity.of(2, "oz"))
                                .price(Price.of(1800, "USD"))
                                .originDateTime(ZonedDateTime.parse("2021-02-01T06:24:11Z"))
                                .build(),
                        PnlDto.PnlTradeDetailsJson.builder()
                                .originTradeId("pm-trade2")
                                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                                .symbol("XAU/USD")
                                .subName("Krugerrand")
                                .side(BUY)
                                .quantity(Quantity.of(2, "oz"))
                                .price(Price.of(1820, "USD"))
                                .originDateTime(ZonedDateTime.parse("2021-03-02T12:14:11Z"))
                                .build(),
                        PnlDto.PnlTradeDetailsJson.builder()
                                .originTradeId("pm-trade3")
                                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                                .symbol("XAU/USD")
                                .subName("Maple Leaf")
                                .side(SELL)
                                .quantity(Quantity.of(1, "oz"))
                                .price(Price.of(1850, "USD"))
                                .originDateTime(ZonedDateTime.parse("2021-04-01T16:24:11Z"))
                                .build(),
                        PnlDto.PnlTradeDetailsJson.builder()
                                .originTradeId("pm-trade4")
                                .portfolioId(registeredPreciousMetalsPortfolio.getPortfolioId())
                                .symbol("XAG/USD")
                                .subName("Maple Leaf")
                                .side(BUY)
                                .quantity(Quantity.of(5, "oz"))
                                .price(Price.of(90, "USD"))
                                .originDateTime(ZonedDateTime.parse("2021-03-02T12:14:11Z"))
                                .build()));

        // find portfolio-statement for second portfolio
        PnlDto.PnlPortfolioStatementJson statementOfSecondPortfolio = pnlHistoryJson.getPnlStatements().get(0).getPortfolioStatements().stream()
                .filter(pnlPortfolioStatementJson -> pnlPortfolioStatementJson.getPortfolioId().equals(registeredPreciousMetalsPortfolio2.getPortfolioId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("portfolio-statement is missing"));


        assertThat(statementOfSecondPortfolio.getExecutedTrades())
                .usingElementComparatorIgnoringFields("tradeId")
                .isEqualTo(List.of(
                        PnlDto.PnlTradeDetailsJson.builder()
                                .originTradeId("pm-trade5")
                                .portfolioId(registeredPreciousMetalsPortfolio2.getPortfolioId())
                                .symbol("XAU/USD")
                                .subName("Maple Leaf")
                                .side(BUY)
                                .quantity(Quantity.of(1, "oz"))
                                .price(Price.of(2000, "USD"))
                                .originDateTime(ZonedDateTime.parse("2021-02-01T06:24:11Z"))
                                .build()));
    }

    @Test
    public void test() {
        PnlHistory aggregate = PnlHistory.builder()
                .pnlId(null)
                .userId(UserId.of("12345"))
                .pnlStatements(List.of(
                        PnlStatement.builder()
                                .investedBalance(Money.of(100, "USD"))
                                .currentValue(Money.of(120, "USD"))
                                .totalProfit(Money.of(20, "USD"))
                                .pctProfit(20)
                                .pnlPortfolioStatements(List.of(
                                        PnlPortfolioStatement.builder()
                                                .portfolioId(PortfolioId.of(UUID.randomUUID().toString()))
                                                .investedBalance(Money.of(100, "USD"))
                                                .currentValue(Money.of(120, "USD"))
                                                .totalProfit(Money.of(20, "USD"))
                                                .pctProfit(20)
                                                .executedTrades(List.of(
                                                        PnlTradeDetails.builder()
                                                                .tradeId(TradeId.of(UUID.randomUUID().toString()))
                                                                .originTradeId(OriginTradeId.of("T1"))
                                                                .portfolioId(PortfolioId.of("P123"))
                                                                .symbol(Symbol.of("BTC/USD"))
                                                                .subName(SubName.of("crypto"))
                                                                .side(BUY)
                                                                .quantity(Quantity.of(0.5))
                                                                .price(Price.of(30000, "USD"))
                                                                .originDateTime(ZonedDateTime.parse("2021-07-01T06:30:00Z"))
                                                                .build(),
                                                        PnlTradeDetails.builder()
                                                                .tradeId(TradeId.of(UUID.randomUUID().toString()))
                                                                .originTradeId(OriginTradeId.of("T2"))
                                                                .portfolioId(PortfolioId.of("P123"))
                                                                .symbol(Symbol.of("BTC/USD"))
                                                                .subName(SubName.of("crypto"))
                                                                .side(SELL)
                                                                .quantity(Quantity.of(0.4))
                                                                .price(Price.of(35000, "USD"))
                                                                .originDateTime(ZonedDateTime.parse("2021-07-01T07:30:00Z"))
                                                                .build()
                                                ))
                                                .build()))
                                .dateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                                .build(),
                        PnlStatement.builder()
                                .investedBalance(Money.of(100, "USD"))
                                .currentValue(Money.of(130, "USD"))
                                .totalProfit(Money.of(30, "USD"))
                                .pctProfit(30)
                                .pnlPortfolioStatements(List.of(
                                        PnlPortfolioStatement.builder()
                                                .portfolioId(PortfolioId.of(UUID.randomUUID().toString()))
                                                .investedBalance(Money.of(100, "USD"))
                                                .currentValue(Money.of(130, "USD"))
                                                .totalProfit(Money.of(30, "USD"))
                                                .pctProfit(30)
                                                .executedTrades(List.of(
                                                        PnlTradeDetails.builder()
                                                                .tradeId(TradeId.of(UUID.randomUUID().toString()))
                                                                .originTradeId(OriginTradeId.of("T3"))
                                                                .portfolioId(PortfolioId.of("P123"))
                                                                .symbol(Symbol.of("ETH/USD"))
                                                                .subName(SubName.of("crypto"))
                                                                .side(BUY)
                                                                .quantity(Quantity.of(0.5))
                                                                .price(Price.of(2000, "USD"))
                                                                .originDateTime(ZonedDateTime.parse("2021-07-01T06:30:00Z"))
                                                                .build(),
                                                        PnlTradeDetails.builder()
                                                                .tradeId(TradeId.of(UUID.randomUUID().toString()))
                                                                .originTradeId(OriginTradeId.of("T4"))
                                                                .portfolioId(PortfolioId.of("P123"))
                                                                .symbol(Symbol.of("ETH/USD"))
                                                                .subName(SubName.of("crypto"))
                                                                .side(SELL)
                                                                .quantity(Quantity.of(0.4))
                                                                .price(Price.of(2100, "USD"))
                                                                .originDateTime(ZonedDateTime.parse("2021-07-01T07:30:00Z"))
                                                                .build()
                                                ))
                                                .build()
                                ))
                                .dateTime(ZonedDateTime.parse("2021-07-01T06:30:00Z"))
                                .build()
                ))
                .build();

        PnlHistory persistedPnlHistory = pnlRepository.save(aggregate);
        Optional<PnlHistory> byUser = pnlRepository.findByUser(UserId.of("12345"));

        System.out.println(persistedPnlHistory);
    }
}
