package com.multi.vidulum.portfolio.app;


import com.multi.vidulum.FixedClockConfig;
import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(classes = FixedClockConfig.class)
@Import({PortfolioAppConfig.class})
@Testcontainers
@DirtiesContext
@ExtendWith(SpringExtension.class)
@EmbeddedKafka(partitions = 1, brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
class PortfolioTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.4.6");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
    }

    @Autowired
    private PortfolioFactory portfolioFactory;

    @Autowired
    private DomainPortfolioRepository portfolioRepository;

    @Test
    public void shouldOpenEmptyPortfolioTest() {
        Portfolio portfolio = portfolioFactory.empty(
                PortfolioId.generate(),
                "XYZ",
                UserId.of("User"),
                Broker.of("Broker")
        );

        portfolio.depositMoney(
                Money.of(10000, "USD"),
                AssetBasicInfo.builder()
                        .ticker(Ticker.of("USD"))
                        .fullName("American Dollar")
                        .segment(Segment.of("cash"))
                        .tags(List.of("cash"))
                        .build());
        Portfolio savedPortfolio = portfolioRepository.save(portfolio);



        List<DomainEvent> domainEvents = portfolioRepository.findDomainEvents(savedPortfolio.getPortfolioId());

        System.out.println(domainEvents);
        assertThat(domainEvents)
                .containsExactlyInAnyOrder(
                        new PortfolioEvents.PortfolioOpenedEvent(
                                portfolio.getPortfolioId(),
                                "XYZ",
                                Broker.of("Broker")
                        ),
                        new PortfolioEvents.MoneyDepositedEvent(
                                AssetBasicInfo.builder()
                                        .ticker(Ticker.of("USD"))
                                        .fullName("American Dollar")
                                        .segment(Segment.of("cash"))
                                        .tags(List.of("cash"))
                                        .build(),
                                Money.of(10000, "USD"))
                );
    }

}