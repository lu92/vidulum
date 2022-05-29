package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.quotation.app.QuotationDto;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.trading.domain.IntegrationTest;
import com.multi.vidulum.user.app.UserDto;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static com.multi.vidulum.common.Side.BUY;

public class DegiroSimulationTest extends IntegrationTest {

    @Test
    public void test() {
        quoteRestController.changePrice("DEGIRO", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("DEGIRO", "USD", "USD", 1, "USD", 0);
        quoteRestController.changePrice("DEGIRO", "EUR", "EUR", 1, "EUR", 0);
        quoteRestController.changePrice("DEGIRO", "USD", "EUR", 0.95, "EUR", 0);
        quoteRestController.changePrice("DEGIRO", "VUSA", "EUR", 70.778, "EUR", 0);
        quoteRestController.changePrice("DEGIRO", "EUR", "USD", 1.05, "USD", 0);

        quoteRestController.registerAssetBasicInfo("DEGIRO", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("USD")
                .fullName("American Dollar")
                .segment("Cash")
                .tags(List.of())
                .build());
        quoteRestController.registerAssetBasicInfo("DEGIRO", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("VUSA")
                .fullName("Vanguard S&P 500 UCITS ETF USD")
                .segment("stock")
                .tags(List.of("VUSA"))
                .build());

        UserDto.UserSummaryJson createdUserJson = createUser("lu92", "secret", "lu92@email.com");
        userRestController.activateUser(createdUserJson.getUserId());
        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio = registerPortfolio("XYZ", "DEGIRO", createdUserJson.getUserId(), "EUR");


        PortfolioId registeredPortfolioId = PortfolioId.of(registeredPortfolio.getPortfolioId());
        depositMoney(registeredPortfolioId, Money.of(4302, "EUR"));
        depositMoney(registeredPortfolioId, Money.of(7598.45, "EUR"));

        makeDirectTrade(
                PortfolioId.of(registeredPortfolio.getPortfolioId()),
                registeredPortfolio.getAllowedDepositCurrency(),
                DirectTrade.builder()
                        .originOrderId(UUID.randomUUID().toString())
                        .portfolioId(registeredPortfolio.getPortfolioId())
                        .broker(registeredPortfolio.getBroker())
                        .symbol("VUSA/EUR")
                        .side(BUY)
                        .quantity(Quantity.of(10))
                        .price(Price.of(70.778, "EUR"))
                        .exchangeCurrencyFee(Money.of(0, "EUR"))
                        .transactionFee(Money.of(3.90, "EUR"))
                        .originDateTime(ZonedDateTime.parse("2022-05-20T15:39:41Z"))
                        .build()
        );


        System.out.println("END");
    }
}
