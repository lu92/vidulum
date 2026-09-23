package com.multi.vidulum.portfolio;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.OrderType;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.trading.app.TradingDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;

/**
 * One person's view of the portfolio, order and trade endpoints.
 *
 * <p>Follows the actor pattern the project's integration tests use
 * ({@code CashFlowHttpActor}, {@code ExchangeConnectionHttpActor}), with one difference that is
 * the point of it: <b>the token belongs to the actor</b>. Two actors are two people, and a test
 * reads as "Alice does this, Bob is refused that" instead of juggling headers.
 *
 * <p>Each call has an {@code …ExpectingError} twin returning {@link ApiError}, because for these
 * endpoints the refusals are the behaviour under test.
 */
@Slf4j
public class PortfolioHttpActor {

    private final TestRestTemplate restTemplate;
    private final String baseUrl;
    private final String jwtToken;
    private final String userId;

    public PortfolioHttpActor(TestRestTemplate restTemplate, int port, String jwtToken, String userId) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
        this.jwtToken = jwtToken;
        this.userId = userId;
    }

    public String userId() {
        return userId;
    }

    // --- portfolio ---------------------------------------------------------------------------

    public ResponseEntity<PortfolioDto.PortfolioSummaryJson> createPortfolio(String name, String broker, String currency) {
        PortfolioDto.CreateEmptyPortfolioJson request = PortfolioDto.CreateEmptyPortfolioJson.builder()
                .name(name)
                .broker(broker)
                .allowedDepositCurrency(currency)
                .build();
        return post("/portfolio", request, PortfolioDto.PortfolioSummaryJson.class);
    }

    public ResponseEntity<PortfolioDto.PortfolioSummaryJson> getPortfolio(String portfolioId, String currency) {
        return get("/portfolio/" + portfolioId + "/" + currency, PortfolioDto.PortfolioSummaryJson.class);
    }

    public ResponseEntity<ApiError> getPortfolioExpectingError(String portfolioId, String currency) {
        return get("/portfolio/" + portfolioId + "/" + currency, ApiError.class);
    }

    public ResponseEntity<PortfolioDto.AggregatedPortfolioSummaryJson> getAggregatedPortfolio(String currency) {
        return get("/aggregated-portfolio/" + currency, PortfolioDto.AggregatedPortfolioSummaryJson.class);
    }

    public ResponseEntity<Void> deposit(String portfolioId, Money money) {
        return post("/portfolio/deposit", depositRequest(portfolioId, money), Void.class);
    }

    public ResponseEntity<ApiError> depositExpectingError(String portfolioId, Money money) {
        return post("/portfolio/deposit", depositRequest(portfolioId, money), ApiError.class);
    }

    private static PortfolioDto.DepositMoneyJson depositRequest(String portfolioId, Money money) {
        return PortfolioDto.DepositMoneyJson.builder().portfolioId(portfolioId).money(money).build();
    }

    // --- orders ------------------------------------------------------------------------------

    public ResponseEntity<TradingDto.OrderSummaryJson> placeOrder(String portfolioId, String symbol, Side side,
                                                                 Price limitPrice, Quantity quantity) {
        return post("/orders", orderRequest(portfolioId, symbol, side, limitPrice, quantity),
                TradingDto.OrderSummaryJson.class);
    }

    public ResponseEntity<ApiError> placeOrderExpectingError(String portfolioId, String symbol, Side side,
                                                             Price limitPrice, Quantity quantity) {
        return post("/orders", orderRequest(portfolioId, symbol, side, limitPrice, quantity), ApiError.class);
    }

    private TradingDto.PlaceOrderJson orderRequest(String portfolioId, String symbol, Side side,
                                                   Price limitPrice, Quantity quantity) {
        return TradingDto.PlaceOrderJson.builder()
                .originOrderId("origin-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .portfolioId(portfolioId)
                .broker("PM")
                .symbol(symbol)
                .type(OrderType.LIMIT)
                .side(side)
                .limitPrice(limitPrice)
                .quantity(quantity)
                .originDateTime(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                .build();
    }

    // --- trades ------------------------------------------------------------------------------

    /** A purchase entered by hand — no order precedes it (VID-180). */
    public ResponseEntity<Void> tradeByHand(String portfolioId, String symbol, Side side,
                                            Quantity quantity, Price price) {
        return post("/trades", tradeRequest(portfolioId, symbol, side, quantity, price), Void.class);
    }

    public ResponseEntity<ApiError> tradeByHandExpectingError(String portfolioId, String symbol, Side side,
                                                              Quantity quantity, Price price) {
        return post("/trades", tradeRequest(portfolioId, symbol, side, quantity, price), ApiError.class);
    }

    private TradingDto.TradeExecutedJson tradeRequest(String portfolioId, String symbol, Side side,
                                                      Quantity quantity, Price price) {
        return TradingDto.TradeExecutedJson.builder()
                .originTradeId("trade-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .portfolioId(portfolioId)
                .orderId("")
                .symbol(symbol)
                .subName("")
                .side(side)
                .quantity(quantity)
                .price(price)
                .fee(TradingDto.Fee.builder()
                        .exchangeCurrencyFee(Money.zero("USD"))
                        .transactionFee(Money.zero("USD"))
                        .build())
                .originDateTime(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                .build();
    }

    // --- plumbing ----------------------------------------------------------------------------

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> responseType) {
        return restTemplate.exchange(baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, headers()), responseType);
    }

    private <T> ResponseEntity<T> get(String path, Class<T> responseType) {
        return restTemplate.exchange(baseUrl + path, HttpMethod.GET,
                new HttpEntity<>(headers()), responseType);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtToken);
        return headers;
    }
}
