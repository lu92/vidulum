package com.multi.vidulum.trading;

import com.multi.vidulum.WealthTestApplication;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.trading.infrastructure.TradeEntity;
import com.multi.vidulum.trading.infrastructure.TradeMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether the uniqueness actually exists in the database (task F1).
 *
 * <p>The handler checks before writing, and that check loses a race by construction: two deliveries
 * can both find nothing and both write. Only an index decides. So this is the level that has to
 * prove it — and it proves two things at once, because the index nearly did not exist at all:
 * {@code auto-index-creation} defaults to {@code false} in Spring Boot, which had quietly turned
 * every index annotation in this codebase into a comment.
 */
@SpringBootTest(
        classes = {WealthTestApplication.class, FixedClockConfig.class},
        // Deliberately the only spelling set here: Boot 4 moved the connection properties to
        // spring.mongodb, but this one still lives under spring.data.mongodb — and with the other
        // spelling the index silently does not exist, which this test caught.
        properties = "spring.data.mongodb.auto-index-creation=true")
@ActiveProfiles("test")
class TradeUniquenessIndexTest {

    private static final MongoDBContainer MONGO;
    private static final KafkaContainer KAFKA;

    static {
        MONGO = new MongoDBContainer("mongo:8.0.4");
        MONGO.start();
        // The module's context wires Kafka producers; nothing here publishes, but the beans exist.
        KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"));
        KAFKA.start();
    }

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("mongodb.port", MONGO::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private TradeMongoRepository trades;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void clean() {
        trades.deleteAll();
    }

    private static TradeEntity trade(String tradeId, String portfolioId, String originTradeId) {
        return TradeEntity.builder()
                .tradeId(tradeId)
                .userId("U10000001")
                .portfolioId(portfolioId)
                .originTradeId(originTradeId)
                .subName("traded")
                .symbol("BTC/USD")
                .side(Side.BUY)
                .quantity(Quantity.of(1))
                .price(Price.of(40_000, "USD"))
                .fee(new TradeEntity.FeeEntity(
                        Money.zero("USD"), Money.zero("USD"), Money.zero("USD")))
                .localValue(Money.of(40_000, "USD"))
                .value(Money.of(40_000, "USD"))
                .totalValue(Money.of(40_000, "USD"))
                .originDateTime(new Date())
                .build();
    }

    @Test
    void shouldCreateTheIndexRatherThanOnlyDeclareIt() {
        assertThat(mongoTemplate.indexOps(TradeEntity.class).getIndexInfo())
                .as("annotations create nothing unless auto-index-creation is on")
                .anySatisfy(index -> assertThat(index.getName()).isEqualTo("trade_origin_unique"));
    }

    @Test
    void shouldRefuseASecondTradeUnderTheSameOriginIdInOnePortfolio() {
        trades.save(trade("trade-1", "portfolio-1", "okx-1"));

        assertThatThrownBy(() -> trades.save(trade("trade-2", "portfolio-1", "okx-1")))
                .as("the check in the handler can be raced; this cannot")
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(trades.findAll()).hasSize(1);
    }

    /** Two exchanges may mint the same string, and both owners' trades are real. */
    @Test
    void shouldAllowTheSameOriginIdInAnotherPortfolio() {
        trades.save(trade("trade-1", "portfolio-1", "fill-7"));
        trades.save(trade("trade-2", "portfolio-2", "fill-7"));

        assertThat(trades.findAll()).hasSize(2);
    }

    /** Identity read back by what it was called at the origin — the lookup the handler deduplicates on. */
    @Test
    void shouldFindATradeByWhatItsOriginCalledIt() {
        trades.save(trade("trade-1", "portfolio-1", "okx-1"));

        assertThat(trades.findByPortfolioIdAndOriginTradeId("portfolio-1", "okx-1"))
                .isPresent()
                .hasValueSatisfying(found -> assertThat(found.getTradeId()).isEqualTo("trade-1"));
        assertThat(trades.findByPortfolioIdAndOriginTradeId("portfolio-1", "nothing-like-it"))
                .isEmpty();
    }
}
