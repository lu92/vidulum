package com.multi.vidulum.pnl;

import com.multi.vidulum.WealthTestApplication;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.pnl.domain.PnlId;
import com.multi.vidulum.pnl.domain.PnlPortfolioStatement;
import com.multi.vidulum.pnl.domain.PnlStatement;
import com.multi.vidulum.pnl.domain.ValuationBasis;
import com.multi.vidulum.quotation.api.fx.FxRate;
import com.multi.vidulum.quotation.api.fx.RateSourceId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a recorded valuation actually survives being written down (task C17).
 *
 * <p>This test exists because of a failure the component tests could not have produced: they save
 * through an in-memory repository, where any object graph round-trips perfectly. The real driver
 * has <b>no codec for {@code ZonedDateTime}</b>, and the rate stored beside each valuation carries
 * one — so on a running container the nightly job logged {@code Recorded 0 of 1} and every
 * statement was lost, while 329 tests stayed green.
 *
 * <p>The lesson is the one this codebase keeps relearning: a claim about storage has to be made
 * against storage. Same reason {@code TradeUniquenessIndexTest} asserts the index is in the
 * database rather than that the annotation is on the class.
 */
@SpringBootTest(classes = {WealthTestApplication.class, FixedClockConfig.class})
@ActiveProfiles("test")
class ValuationBasisPersistenceTest {

    private static final MongoDBContainer MONGO;
    private static final KafkaContainer KAFKA;

    static {
        MONGO = new MongoDBContainer("mongo:8.0.4");
        MONGO.start();
        KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"));
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("mongodb.port", MONGO::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static final RateSourceId NBP = RateSourceId.of("NBP");
    private static final ZonedDateTime FRIDAY = ZonedDateTime.parse("2026-09-25T00:00+02:00[Europe/Warsaw]");
    private static final ZonedDateTime RECORDED = ZonedDateTime.parse("2026-09-27T01:00:00Z");

    @Autowired
    private DomainPnlRepository repository;

    /** Ids have a shape the value object enforces; each test needs its own owner. */
    private int nextOwner = 10_000_001;

    private PnlHistory historyWith(ValuationBasis basis, String currency) {
        return PnlHistory.builder()
                .pnlId(PnlId.of("pnl-" + nextOwner))
                .userId(UserId.of("U" + nextOwner++))
                .pnlStatements(new ArrayList<>(List.of(PnlStatement.builder()
                        .basis(basis)
                        .dateTime(RECORDED)
                        .currentValue(Money.of(38_404, currency))
                        .netContributions(Money.of(38_404, currency))
                        .totalUnrealisedProfit(Money.zero(currency))
                        .pctUnrealisedProfit(0d)
                        .pnlPortfolioStatements(List.of(PnlPortfolioStatement.builder()
                                .portfolioId(PortfolioId.of("portfolio-1"))
                                .currentValue(Money.of(38_404, currency))
                                .netContributions(Money.of(38_404, currency))
                                .executedTrades(List.of())
                                .build()))
                        .build())))
                .build();
    }

    /** The case that failed in production: a valuation whose basis carries a dated rate. */
    @Test
    void shouldStoreAValuationWhoseBasisCarriesARate() {
        PnlHistory saved = repository.save(historyWith(
                new ValuationBasis(Currency.of("PLN"), FxRate.of("PLN", "USD", 0.26, FRIDAY, NBP)),
                "PLN"));

        PnlHistory reloaded = repository.findByUser(saved.getUserId()).orElseThrow();

        ValuationBasis basis = reloaded.getPnlStatements().getFirst().getBasis();
        assertThat(basis.currency()).isEqualTo(Currency.of("PLN"));
        assertThat(basis.rate().rate().getAmount().doubleValue()).isEqualTo(0.26);
        assertThat(basis.rate().source()).isEqualTo(NBP);
    }

    /**
     * The moment survives as an instant. It is written in Warsaw time and read back in UTC, which
     * is the same point in time — what the field states is when the rate applied, not how it was
     * displayed.
     */
    @Test
    void shouldKeepTheMomentTheRateAppliesTo() {
        PnlHistory saved = repository.save(historyWith(
                new ValuationBasis(Currency.of("PLN"), FxRate.of("PLN", "USD", 0.26, FRIDAY, NBP)),
                "PLN"));

        ZonedDateTime asOf = repository.findByUser(saved.getUserId()).orElseThrow()
                .getPnlStatements().getFirst().getBasis().rate().asOf();

        assertThat(asOf.toInstant()).isEqualTo(FRIDAY.toInstant());
    }

    /** The pivot stores no rate, and comes back saying so rather than claiming parity. */
    @Test
    void shouldStoreAPivotValuationWithoutARate() {
        PnlHistory saved = repository.save(
                historyWith(ValuationBasis.pivot(Currency.of("USD")), "USD"));

        ValuationBasis basis = repository.findByUser(saved.getUserId()).orElseThrow()
                .getPnlStatements().getFirst().getBasis();

        assertThat(basis.currency()).isEqualTo(Currency.of("USD"));
        assertThat(basis.knownRate()).isEmpty();
    }

    /**
     * A statement written before C17 has no basis, and reads back as none. Substituting the dollar
     * it probably was would be guessing exactly the fact the field exists to stop being guessed.
     */
    @Test
    void shouldReadAStatementWithNoBasisAsSayingNothing() {
        PnlHistory saved = repository.save(historyWith(null, "USD"));

        assertThat(repository.findByUser(saved.getUserId()).orElseThrow()
                .getPnlStatements().getFirst().getBasis())
                .isNull();
    }
}
