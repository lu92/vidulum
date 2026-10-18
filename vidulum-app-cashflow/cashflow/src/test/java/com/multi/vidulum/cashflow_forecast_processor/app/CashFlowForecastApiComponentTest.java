package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.TestIds;
import com.multi.vidulum.cashflow.api.forecast.MonthStatus;
import com.multi.vidulum.cashflow.api.forecast.MonthStatuses;
import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The forecast answers its own contract, from the statement it really maintains.
 *
 * <p>No Spring and no Mongo: the implementation needs one repository, so it gets an in-memory one.
 * What this covers that {@code MonthStatusesTest} cannot is the half that can drift - the walk over
 * the statement and the mapping from the read model's status to the contract's.
 */
class CashFlowForecastApiComponentTest {

    private final InMemoryStatements statements = new InMemoryStatements();
    private final CashFlowForecastApiImpl api = new CashFlowForecastApiImpl(statements);

    @Test
    void itAnswersTheStatusOfEveryMonthTheStatementHolds() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        statements.save(statementOf(cashFlowId, new LinkedHashMap<>(Map.of(
                YearMonth.of(2026, 1), CashFlowMonthlyForecast.Status.IMPORTED,
                YearMonth.of(2026, 2), CashFlowMonthlyForecast.Status.ATTESTED,
                YearMonth.of(2026, 3), CashFlowMonthlyForecast.Status.ACTIVE,
                YearMonth.of(2026, 4), CashFlowMonthlyForecast.Status.FORECASTED))));

        MonthStatuses statuses = api.monthStatusesOf(cashFlowId);

        assertThat(statuses.cashFlowId()).isEqualTo(cashFlowId);
        assertThat(statuses.byMonth()).containsExactlyInAnyOrderEntriesOf(Map.of(
                YearMonth.of(2026, 1), MonthStatus.IMPORTED,
                YearMonth.of(2026, 2), MonthStatus.ATTESTED,
                YearMonth.of(2026, 3), MonthStatus.ACTIVE,
                YearMonth.of(2026, 4), MonthStatus.FORECASTED));
    }

    /**
     * Task F38, at the level that owns the statuses. An attested month is read-only, and before
     * this contract existed nothing in any test could say so: the one consumer learned month
     * statuses through a test double that generated them from the periods.
     */
    @Test
    void anAttestedMonthRefusesAnImportWhileItsNeighboursAcceptOne() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        statements.save(statementOf(cashFlowId, new LinkedHashMap<>(Map.of(
                YearMonth.of(2026, 1), CashFlowMonthlyForecast.Status.IMPORTED,
                YearMonth.of(2026, 2), CashFlowMonthlyForecast.Status.ATTESTED,
                YearMonth.of(2026, 3), CashFlowMonthlyForecast.Status.ACTIVE))));

        MonthStatuses statuses = api.monthStatusesOf(cashFlowId);

        assertThat(statuses.acceptsImportAt(YearMonth.of(2026, 2))).isFalse();
        assertThat(statuses.acceptsImportAt(YearMonth.of(2026, 1))).isTrue();
        assertThat(statuses.acceptsImportAt(YearMonth.of(2026, 3))).isTrue();
    }

    /**
     * The vocabularies are two enums on purpose - a contract may not expose the read model's own
     * type - so this is what keeps them in step. Fails if either side gains a constant the other
     * lacks, in the same spirit as {@code TransactionClassificationVocabularyTest}.
     */
    @Test
    void everyStatusTheReadModelCanHoldHasACounterpartInTheContract() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        Map<YearMonth, CashFlowMonthlyForecast.Status> oneMonthEach = new LinkedHashMap<>();
        YearMonth month = YearMonth.of(2026, 1);
        for (CashFlowMonthlyForecast.Status status : CashFlowMonthlyForecast.Status.values()) {
            oneMonthEach.put(month, status);
            month = month.plusMonths(1);
        }
        statements.save(statementOf(cashFlowId, oneMonthEach));

        MonthStatuses statuses = api.monthStatusesOf(cashFlowId);

        assertThat(statuses.byMonth()).hasSize(CashFlowMonthlyForecast.Status.values().length);
        oneMonthEach.forEach((m, readModelStatus) ->
                assertThat(statuses.at(m))
                        .as("%s", readModelStatus)
                        .contains(MonthStatus.valueOf(readModelStatus.name())));
    }

    @Test
    void aCashFlowWhoseStatementHasNotBeenBuiltYetHasNoMonths() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();

        MonthStatuses statuses = api.monthStatusesOf(cashFlowId);

        assertThat(statuses.cashFlowId()).isEqualTo(cashFlowId);
        assertThat(statuses.isEmpty()).isTrue();
        assertThat(statuses.acceptsImportAt(YearMonth.of(2026, 3))).isFalse();
    }

    @Test
    void aStatementWithoutForecastsHasNoMonthsEither() {
        CashFlowId cashFlowId = TestIds.nextCashFlowId();
        statements.save(statementOf(cashFlowId, new LinkedHashMap<>()));

        assertThat(api.monthStatusesOf(cashFlowId).isEmpty()).isTrue();
    }

    private static CashFlowForecastStatement statementOf(
            CashFlowId cashFlowId,
            Map<YearMonth, CashFlowMonthlyForecast.Status> months) {

        Map<YearMonth, CashFlowMonthlyForecast> forecasts = new LinkedHashMap<>();
        months.forEach((month, status) -> forecasts.put(month, forecastOf(month, status)));

        return new CashFlowForecastStatement(
                cashFlowId,
                forecasts,
                BankAccountNumber.fromIban("GB29NWBK60161331926819", Currency.of("USD")),
                new CurrentCategoryStructure(new ArrayList<>(), new ArrayList<>(), ZonedDateTime.now()),
                null,
                null
        );
    }

    private static CashFlowMonthlyForecast forecastOf(YearMonth period, CashFlowMonthlyForecast.Status status) {
        return new CashFlowMonthlyForecast(
                period,
                new CashFlowStats(
                        Money.zero("USD"),
                        Money.zero("USD"),
                        Money.zero("USD"),
                        new CashSummary(Money.zero("USD"), Money.zero("USD"), Money.zero("USD")),
                        new CashSummary(Money.zero("USD"), Money.zero("USD"), Money.zero("USD"))
                ),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                status,
                null
        );
    }

    /**
     * Not {@code InMemoryForecastStatementRepository} from the infrastructure package: that one is
     * package-private where it lives, and the subject of this test is package-private here. Two
     * methods is a cheaper price than widening either.
     */
    private static final class InMemoryStatements implements CashFlowForecastStatementRepository {

        private final Map<String, CashFlowForecastStatement> store = new HashMap<>();

        @Override
        public Optional<CashFlowForecastStatement> findByCashFlowId(CashFlowId cashFlowId) {
            return Optional.ofNullable(store.get(cashFlowId.id()));
        }

        @Override
        public void save(CashFlowForecastStatement statement) {
            store.put(statement.getCashFlowId().id(), statement);
        }
    }
}
