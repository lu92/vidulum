package com.multi.vidulum;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.quotation.app.QuoteRestController;
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

    @Test
    void shouldReturnCustomersWithRatingGreater90AsVIP() {

        quoteRestController.changePrice("BINANCE", "BTC", "USD", 60000, "USD", 4.2);
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);

        PortfolioDto.PortfolioSummaryJson createdPortfolio = portfolioRestController.createEmptyPortfolio(
                PortfolioDto.CreateEmptyPortfolioJson.builder()
                        .broker("BINANCE")
                        .name("XYZ")
                        .userId("Lucjan Bik")
                        .build());

        portfolioRestController.depositMoney(
                PortfolioDto.DepositMoneyJson.builder()
                        .portfolioId(createdPortfolio.getPortfolioId())
                        .money(Money.of(100000.0, "USD"))
                        .build());

        portfolioRestController.applyTrade(PortfolioDto.TradeExecutedJson.builder()
                .tradeId("tradeID")
                .portfolioId(createdPortfolio.getPortfolioId())
                .symbol("BTC/USD")
                .side(Side.BUY)
                .quantity(0.5)
                .price(Money.of(60030.50, "USD"))
                .build());

        PortfolioDto.PortfolioSummaryJson retrievedPortfolio = portfolioRestController.getPortfolio(createdPortfolio.getPortfolioId());
    }
}
