package com.multi.vidulum.trading.domain;

import com.multi.vidulum.FixedClockConfig;
import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.app.TradingAppConfig;
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

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(classes = FixedClockConfig.class)
@Import({PortfolioAppConfig.class, TradingAppConfig.class})
@Testcontainers
@DirtiesContext
@ExtendWith(SpringExtension.class)
@EmbeddedKafka(partitions = 1, brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
class OrderTest {

    private static final Broker BROKER = Broker.of("Broker");


    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.4.6");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
    }


    @Autowired
    private OrderFactory orderFactory;

    @Autowired
    private DomainOrderRepository orderRepository;

    @Test
    public void shouldCreateOrder() {
        OrderId orderId = OrderId.generate();
        PortfolioId portfolioId = PortfolioId.generate();
        Order order = orderFactory.empty(
                orderId,
                OriginOrderId.notDefined(),
                portfolioId,
                BROKER,
                Symbol.of("BTC/USD"),
                OrderType.LIMIT,
                Side.BUY,
                null,
                null,
                Price.of(30000.0, "USD"),
                Quantity.of(0.2),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"));

        Order savedOrder = orderRepository.save(order);

        assertThat(savedOrder).isEqualTo(
                Order.builder()
                        .orderId(orderId)
                        .originOrderId(OriginOrderId.notDefined())
                        .portfolioId(portfolioId)
                        .broker(BROKER)
                        .symbol(Symbol.of("BTC/USD"))
                        .type(OrderType.LIMIT)
                        .side(Side.BUY)
                        .targetPrice(null)
                        .stopPrice(null)
                        .limitPrice(Price.of(30000.0, "USD"))
                        .quantity(Quantity.of(0.2))
                        .occurredDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .status(OrderStatus.OPEN)
                        .build()
        );

        assertThat(orderRepository.findDomainEvents(savedOrder.getOrderId()))
                .containsExactlyInAnyOrder(
                        new OrderEvents.OrderCreatedEvent(
                                orderId,
                                OriginOrderId.notDefined(),
                                portfolioId,
                                BROKER,
                                Symbol.of("BTC/USD"),
                                OrderType.LIMIT,
                                Side.BUY,
                                null,
                                null,
                                Price.of(30000.0, "USD"),
                                Quantity.of(0.2),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z")
                        )
                );

        assertThat(savedOrder.getUncommittedEvents()).isEmpty();

    }

}