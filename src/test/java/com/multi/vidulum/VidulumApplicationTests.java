package com.multi.vidulum;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.quotation.app.QuoteRestController;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static com.multi.vidulum.common.Side.BUY;

@SpringBootTest
@Import(PortfolioAppConfig.class)
@Testcontainers
@DirtiesContext
@EmbeddedKafka(partitions = 1, brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
//@DataMongoTest(excludeAutoConfiguration = EmbeddedMongoAutoConfiguration.class)
class VidulumApplicationTests {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.4.2");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private QuoteRestController quoteRestController;

    @Autowired
    private PortfolioRestController portfolioRestController;

    @Autowired
    private DomainPortfolioRepository portfolioRepository;

    @Test
    void shouldReturnCustomersWithRatingGreater90AsVIP() {

        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "ETH", "USD", 2850, "USD", 1.09);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);

        PortfolioDto.PortfolioSummaryJson createdPortfolioJson = portfolioRestController.createEmptyPortfolio(
                PortfolioDto.CreateEmptyPortfolioJson.builder()
                        .broker("BINANCE")
                        .name("XYZ")
                        .userId("Lucjan Bik")
                        .build());

        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(createdPortfolioJson.getPortfolioId())
                        .money(Money.of(100000.0, "USD"))
                        .build());

        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
                .tradeId("trade1")
                .portfolioId(createdPortfolioJson.getPortfolioId())
                .symbol("BTC/USD")
                .side(BUY)
                .quantity(0.1)
                .price(Money.of(61000.0, "USD"))
                .build());

//        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
//                .tradeId("trade2")
//                .portfolioId(createdPortfolioJson.getPortfolioId())
//                .symbol("BTC/USD")
//                .side(BUY)
//                .quantity(0.2)
//                .price(Money.of(65000, "USD"))
//                .build());
//
//        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
//                .tradeId("trade2")
//                .portfolioId(createdPortfolioJson.getPortfolioId())
//                .symbol("BTC/USD")
//                .side(BUY)
//                .quantity(0.15)
//                .price(Money.of(55000, "USD"))
//                .build());

        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
                .tradeId("trade3")
                .portfolioId(createdPortfolioJson.getPortfolioId())
                .symbol("ETH/USD")
                .side(BUY)
                .quantity(0.75)
                .price(Money.of(2800, "USD"))
                .build());

        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
                .tradeId("trade4")
                .portfolioId(createdPortfolioJson.getPortfolioId())
                .symbol("ETH/USD")
                .side(BUY)
                .quantity(0.25)
                .price(Money.of(2800, "USD"))
                .build());


        PortfolioDto.PortfolioSummaryJson retrievedPortfolio = portfolioRestController.getPortfolio(createdPortfolioJson.getPortfolioId());

        Optional<Portfolio> optionalPortfolio = portfolioRepository.findById(PortfolioId.of(createdPortfolioJson.getPortfolioId()));
        Assertions.assertThat(optionalPortfolio.isPresent()).isTrue();
        Portfolio portfolio = optionalPortfolio.get();
        System.out.println(portfolio);


        Portfolio expectedPortfolio = Portfolio.builder()
                .portfolioId(PortfolioId.of(createdPortfolioJson.getPortfolioId()))
                .userId(UserId.of("Lucjan Bik"))
                .name("XYZ")
                .broker(Broker.of("BINANCE"))
                .assets(List.of(
                        Asset.builder()
                                .ticker(Ticker.of("USD"))
                                .fullName("")
                                .avgPurchasePrice(Money.one("USD"))
                                .quantity(91100.0)
                                .tags(List.of("currency", "USD"))
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("BTC"))
                                .fullName("Not found")
                                .avgPurchasePrice(Money.of(61000, "USD"))
                                .quantity(0.1)
                                .tags(List.of())
                                .build(),
                        Asset.builder()
                                .ticker(Ticker.of("ETH"))
                                .fullName("Not found")
                                .avgPurchasePrice(Money.of(2800.0, "USD"))
                                .quantity(1)
                                .tags(List.of())
                                .build()
                ))
                .investedBalance(Money.of(100000.0, "USD"))
                .build();

        Assertions.assertThat(portfolio).isEqualTo(expectedPortfolio);
    }
}
