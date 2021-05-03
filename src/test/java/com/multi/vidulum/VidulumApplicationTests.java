package com.multi.vidulum;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(PortfolioAppConfig.class)

@Testcontainers
//@DataMongoTest(excludeAutoConfiguration = EmbeddedMongoAutoConfiguration.class)
class VidulumApplicationTests {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.4.2");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CommandGateway commandGateway;


    @Autowired
    private PortfolioFactory portfolioFactory;

    @Autowired
    private DomainPortfolioRepository portfolioRepository;

    @Test
    void shouldReturnCustomersWithRatingGreater90AsVIP() {
        UserId userId = UserId.of("Lucjan");
        Portfolio newPortfolio = portfolioFactory.createEmptyPortfolio("XYZ", userId, Broker.of("BINANCE"));
        newPortfolio.depositMoney(Money.of(200, "USD"));

        Portfolio savedPortfolio = portfolioRepository.save(newPortfolio);
        System.out.println(savedPortfolio);


//        AssetBasicInfo pslvBasicInfo = new AssetBasicInfo(
//                Ticker.of("PSLV"),
//                "Sprott Silver Trust",
//                List.of("PM", "Silver")
//        );
//
//        newPortfolio.handleExecutedTrade(
//                BuyTrade.builder()
//                        .portfolioId(newPortfolio.getPortfolioId())
//                        .tradeId(TradeId.of("XXX1"))
//                        .ticker(Ticker.of("PSLV"))
//                        .quantity(15)
//                        .price(Money.of(11.50, "USD"))
//                        .build(),
//                pslvBasicInfo);
    }
}
