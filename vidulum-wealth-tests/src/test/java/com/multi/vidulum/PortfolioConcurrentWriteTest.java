package com.multi.vidulum;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionId;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two writers who both read the same portfolio: one wins, the other is told, and the winner's work
 * survives.
 *
 * <p>Task F57, and the entity the task named most urgent. Locking units against an order is
 * read-modify-write, and {@code TradeConfirmedToPortfolio} writes here from a Kafka event - in
 * parallel with whatever the owner is doing in their browser. Until {@code PortfolioEntity} carried
 * a {@code @Version}, {@code save} replaced the whole document, so the later write silently
 * discarded the earlier one: both paths succeeded, and the money or the lock simply was not there
 * afterwards.
 *
 * <p>The race is staged rather than threaded, as in {@code CashFlowConcurrentWriteTest}: two reads
 * followed by two writes is exactly the interleaving that loses data, and staging it makes the
 * assertion deterministic.
 */
class PortfolioConcurrentWriteTest extends WealthIntegrationTest {

    private static final ZonedDateTime WHEN = ZonedDateTime.parse("2022-01-01T00:00:00Z");

    @Test
    void shouldRefuseTheSecondWriteFromAStaleReadRatherThanLoseIt() {
        PortfolioId portfolioId = openPortfolio("PF-CONFLICT-1");

        Portfolio firstReader = portfolioRepository.findById(portfolioId).orElseThrow();
        Portfolio secondReader = portfolioRepository.findById(portfolioId).orElseThrow();

        firstReader.depositMoney(Money.of(100, "USD"), ContributionId.of("c-1"), WHEN);
        portfolioRepository.save(firstReader);

        secondReader.depositMoney(Money.of(250, "USD"), ContributionId.of("c-2"), WHEN);

        assertThatThrownBy(() -> portfolioRepository.save(secondReader))
                .as("the second writer read before the first one wrote, so its document has moved on")
                .isInstanceOf(OptimisticLockingFailureException.class);

        Portfolio stored = portfolioRepository.findById(portfolioId).orElseThrow();
        assertThat(stored.getContributions())
                .as("the first deposit survived; before F57 the second save would have erased it")
                .hasSize(1);
    }

    /**
     * The invariant that turned out to matter more than the annotation: after {@code save}, the
     * instance the caller still holds has to know its new version - otherwise its next save looks
     * like an insert and Mongo answers DuplicateKeyException on the id. Several flows save the same
     * aggregate twice, which is how 40 integration tests found this.
     */
    @Test
    void shouldLetTheSameInstanceBeSavedAgainAfterItWasStored() {
        PortfolioId portfolioId = openPortfolio("PF-CONFLICT-2");
        Portfolio portfolio = portfolioRepository.findById(portfolioId).orElseThrow();

        portfolio.depositMoney(Money.of(100, "USD"), ContributionId.of("c-1"), WHEN);
        portfolioRepository.save(portfolio);

        portfolio.depositMoney(Money.of(50, "USD"), ContributionId.of("c-2"), WHEN);
        portfolioRepository.save(portfolio);

        assertThat(portfolioRepository.findById(portfolioId).orElseThrow().getContributions())
                .as("both deposits are stored, and neither save was treated as an insert")
                .hasSize(2);
    }

    private PortfolioId openPortfolio(String id) {
        PortfolioId portfolioId = PortfolioId.of(id);
        Portfolio portfolio = portfolioFactory.empty(
                portfolioId,
                "conflict test",
                UserId.of("U10000001"),
                Broker.of("BINANCE"),
                Currency.of("USD"));
        portfolioRepository.save(portfolio);
        return portfolioId;
    }
}
