package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.app.commands.rollover.RolloverMonthCommand;
import com.multi.vidulum.cashflow.app.commands.rollover.RolloverMonthResult;
import com.multi.vidulum.TestIds;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowMonthlyForecast;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.trading.domain.IntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the month rollover functionality.
 * Tests the ROLLED_OVER status and related flows.
 */
@Slf4j
public class RolloverMonthIntegrationTest extends IntegrationTest {

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

    @Autowired
    private CashFlowRestController cashFlowRestController;

    @Autowired
    private CommandGateway commandGateway;

    @Autowired
    private DomainCashFlowRepository domainCashFlowRepository;

    @Autowired
    private Clock clock;

    private String uniqueCashFlowName() {
        return "RolloverCF-" + NAME_COUNTER.incrementAndGet();
    }

    private String uniqueUserId() {
        return TestIds.nextUserId().getId();
    }

    @Test
    void shouldRolloverMonthAndTransitionToRolledOverStatus() {
        // Given: CashFlow with history in OPEN mode
        String userId = uniqueUserId();
        YearMonth activePeriod = YearMonth.now(clock); // 2022-01 based on FixedClockConfig
        YearMonth startPeriod = activePeriod.minusMonths(3);

        String cashFlowId = createCashFlowWithHistoryAndActivate(startPeriod, userId);
        CashFlowId cfId = new CashFlowId(cashFlowId);

        // Wait for forecast statement to be created (the event was already processed in helper method)
        await().atMost(10, SECONDS).until(() ->
                statementRepository.findByCashFlowId(cfId).isPresent());

        // Verify initial state - activePeriod should be ACTIVE
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(cfId).orElseThrow();
        CashFlowMonthlyForecast activeForecast = statement.getForecasts().get(activePeriod);
        assertThat(activeForecast.getStatus()).isEqualTo(CashFlowMonthlyForecast.Status.ACTIVE);

        // When: Rollover the month
        CashFlowDto.RolloverMonthResponseJson response = cashFlowRestController.rolloverMonth(cashFlowId);

        // Then: Response should indicate successful rollover
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.getRolledOverPeriod()).isEqualTo(activePeriod);
        assertThat(response.getNewActivePeriod()).isEqualTo(activePeriod.plusMonths(1));

        // Wait for rollover event to be processed
        YearMonth rolledOverPeriod = activePeriod;
        YearMonth newActivePeriod = activePeriod.plusMonths(1);
        await().atMost(10, SECONDS).until(() -> {
            Optional<CashFlowForecastStatement> updatedStatement = statementRepository.findByCashFlowId(cfId);
            if (updatedStatement.isEmpty()) return false;
            CashFlowMonthlyForecast rolledForecast = updatedStatement.get().getForecasts().get(rolledOverPeriod);
            CashFlowMonthlyForecast newActiveForecast = updatedStatement.get().getForecasts().get(newActivePeriod);
            return rolledForecast != null &&
                    rolledForecast.getStatus() == CashFlowMonthlyForecast.Status.ROLLED_OVER &&
                    newActiveForecast != null &&
                    newActiveForecast.getStatus() == CashFlowMonthlyForecast.Status.ACTIVE;
        });

        // Verify final state
        CashFlowForecastStatement finalStatement = statementRepository.findByCashFlowId(cfId).orElseThrow();
        assertThat(finalStatement.getForecasts().get(rolledOverPeriod).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.ROLLED_OVER);
        assertThat(finalStatement.getForecasts().get(newActivePeriod).getStatus())
                .isEqualTo(CashFlowMonthlyForecast.Status.ACTIVE);

        // Verify CashFlow aggregate is updated
        CashFlow cashFlow = domainCashFlowRepository.findById(cfId).orElseThrow();
        assertThat(cashFlow.getSnapshot().activePeriod()).isEqualTo(newActivePeriod);

        log.info("Successfully rolled over from {} to {}", rolledOverPeriod, newActivePeriod);
    }

    @Test
    void shouldFailRolloverForSetupModeCashFlow() {
        // Given: CashFlow in SETUP mode (not yet activated)
        String userId = uniqueUserId();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(3);

        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId(userId)
                        .name(uniqueCashFlowName())
                        .description("Test")
                        .bankAccount(CashFlowDto.BankAccountJson.builder()
                                .bankName("Test Bank")
                                .bankAccountNumber(CashFlowDto.BankAccountNumberJson.builder()
                                        .account("PL94345678901234567890123456")
                                        .denomination(CashFlowDto.CurrencyJson.builder().id("USD").build())
                                        .build())
                                .build())
                        .startPeriod(startPeriod.toString())
                        .initialBalance(CashFlowDto.MoneyJson.builder().amount(java.math.BigDecimal.valueOf(1000)).currency("USD").build())
                        .build()
        );

        // When/Then: Rollover should fail
        assertThatThrownBy(() -> cashFlowRestController.rolloverMonth(cashFlowId))
                .isInstanceOf(RolloverNotAllowedException.class)
                .hasMessageContaining("OPEN");

        log.info("Correctly rejected rollover for SETUP mode CashFlow");
    }

    @Test
    void shouldPerformMultipleRolloversSequentially() {
        // Given: CashFlow in OPEN mode
        String userId = uniqueUserId();
        YearMonth activePeriod = YearMonth.now(clock);
        YearMonth startPeriod = activePeriod.minusMonths(3);

        String cashFlowId = createCashFlowWithHistoryAndActivate(startPeriod, userId);
        CashFlowId cfId = new CashFlowId(cashFlowId);

        await().atMost(10, SECONDS).until(() ->
                statementRepository.findByCashFlowId(cfId).isPresent());

        // When: Perform 3 sequential rollovers
        for (int i = 0; i < 3; i++) {
            YearMonth currentActive = activePeriod.plusMonths(i);
            YearMonth nextActive = currentActive.plusMonths(1);

            CashFlowDto.RolloverMonthResponseJson response = cashFlowRestController.rolloverMonth(cashFlowId);

            assertThat(response.getRolledOverPeriod()).isEqualTo(currentActive);
            assertThat(response.getNewActivePeriod()).isEqualTo(nextActive);

            // Wait for rollover to be processed
            YearMonth expectedRolledOver = currentActive;
            YearMonth expectedActive = nextActive;
            await().atMost(10, SECONDS).until(() -> {
                Optional<CashFlowForecastStatement> stmt = statementRepository.findByCashFlowId(cfId);
                if (stmt.isEmpty()) return false;
                CashFlowMonthlyForecast rolledForecast = stmt.get().getForecasts().get(expectedRolledOver);
                CashFlowMonthlyForecast activeForecast = stmt.get().getForecasts().get(expectedActive);
                return rolledForecast != null &&
                        rolledForecast.getStatus() == CashFlowMonthlyForecast.Status.ROLLED_OVER &&
                        activeForecast != null &&
                        activeForecast.getStatus() == CashFlowMonthlyForecast.Status.ACTIVE;
            });

            log.info("Rollover {} completed: {} -> {}", i + 1, currentActive, nextActive);
        }

        // Verify final state
        CashFlow cashFlow = domainCashFlowRepository.findById(cfId).orElseThrow();
        assertThat(cashFlow.getSnapshot().activePeriod()).isEqualTo(activePeriod.plusMonths(3));

        // Verify all rolled over months have ROLLED_OVER status
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(cfId).orElseThrow();
        for (int i = 0; i < 3; i++) {
            YearMonth rolledMonth = activePeriod.plusMonths(i);
            assertThat(statement.getForecasts().get(rolledMonth).getStatus())
                    .isEqualTo(CashFlowMonthlyForecast.Status.ROLLED_OVER);
        }

        log.info("Successfully performed 3 sequential rollovers");
    }

    @Test
    void shouldPerformBatchRolloverCatchUp() {
        // Given: CashFlow in OPEN mode
        String userId = uniqueUserId();
        YearMonth activePeriod = YearMonth.now(clock);
        YearMonth startPeriod = activePeriod.minusMonths(3);
        YearMonth targetPeriod = activePeriod.plusMonths(3);

        String cashFlowId = createCashFlowWithHistoryAndActivate(startPeriod, userId);
        CashFlowId cfId = new CashFlowId(cashFlowId);

        await().atMost(10, SECONDS).until(() ->
                statementRepository.findByCashFlowId(cfId).isPresent());

        // When: Batch rollover to target period
        CashFlowDto.BatchRolloverResponseJson response = cashFlowRestController.rolloverMonthsTo(
                cashFlowId, targetPeriod.toString());

        // Then: Response should indicate all months rolled over
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.getMonthsRolledOver()).isEqualTo(3);
        assertThat(response.getFirstRolledOverPeriod()).isEqualTo(activePeriod);
        assertThat(response.getLastRolledOverPeriod()).isEqualTo(activePeriod.plusMonths(2));
        assertThat(response.getNewActivePeriod()).isEqualTo(targetPeriod);

        // Wait for all rollovers to be processed
        await().atMost(30, SECONDS).until(() -> {
            Optional<CashFlowForecastStatement> stmt = statementRepository.findByCashFlowId(cfId);
            if (stmt.isEmpty()) return false;
            CashFlowMonthlyForecast targetForecast = stmt.get().getForecasts().get(targetPeriod);
            return targetForecast != null && targetForecast.getStatus() == CashFlowMonthlyForecast.Status.ACTIVE;
        });

        // Verify final state
        CashFlow cashFlow = domainCashFlowRepository.findById(cfId).orElseThrow();
        assertThat(cashFlow.getSnapshot().activePeriod()).isEqualTo(targetPeriod);

        log.info("Successfully performed batch rollover: {} months", response.getMonthsRolledOver());
    }

    /**
     * Helper method to create a CashFlow with history and activate it.
     */
    private String createCashFlowWithHistoryAndActivate(YearMonth startPeriod, String userId) {
        // Create CashFlow with history
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId(userId)
                        .name(uniqueCashFlowName())
                        .description("Test CashFlow for rollover")
                        .bankAccount(CashFlowDto.BankAccountJson.builder()
                                .bankName("Test Bank")
                                .bankAccountNumber(CashFlowDto.BankAccountNumberJson.builder()
                                        .account("PL67345678901234567890123457")
                                        .denomination(CashFlowDto.CurrencyJson.builder().id("USD").build())
                                        .build())
                                .build())
                        .startPeriod(startPeriod.toString())
                        .initialBalance(CashFlowDto.MoneyJson.builder().amount(java.math.BigDecimal.valueOf(5000)).currency("USD").build())
                        .build()
        );

        CashFlowId cfId = new CashFlowId(cashFlowId);

        // Wait for CashFlowWithHistoryCreatedEvent to be processed before attesting
        await().atMost(10, SECONDS).until(() ->
                cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.CashFlowWithHistoryCreatedEvent.class.getSimpleName()))
                        .orElse(false));

        Money initialBalance = Money.of(5000, "USD");

        // Attest to activate
        cashFlowRestController.attestHistoricalImport(
                cashFlowId,
                CashFlowDto.AttestHistoricalImportJson.builder()
                        .confirmedBalance(initialBalance)
                        .forceAttestation(false)
                        .build()
        );

        // Wait for HistoricalImportAttestedEvent to be processed
        await().atMost(10, SECONDS).until(() ->
                cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName()))
                        .orElse(false));

        // Verify CashFlow is in OPEN mode
        CashFlow cashFlow = domainCashFlowRepository.findById(cfId).orElseThrow();
        assertThat(cashFlow.getSnapshot().status()).isEqualTo(CashFlow.CashFlowStatus.OPEN);

        return cashFlowId;
    }
}
