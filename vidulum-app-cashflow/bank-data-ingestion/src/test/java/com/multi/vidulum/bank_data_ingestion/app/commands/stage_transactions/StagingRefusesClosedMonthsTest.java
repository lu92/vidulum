package com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions;

import com.multi.vidulum.bank_data_ingestion.app.BankDataIngestionConfig;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.domain.CategoryMappingRepository;
import com.multi.vidulum.bank_data_ingestion.domain.PatternMappingRepository;
import com.multi.vidulum.bank_data_ingestion.domain.StagedTransaction;
import com.multi.vidulum.bank_data_ingestion.domain.StagedTransactionRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagingSessionMongoRepository;
import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.forecast.CashFlowForecastApi;
import com.multi.vidulum.cashflow.api.forecast.MonthStatus;
import com.multi.vidulum.cashflow.api.forecast.MonthStatuses;
import com.multi.vidulum.cashflow.api.view.ImportedTransactions;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.user_financial_profile.api.UserFinancialProfileApi;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Staging is stricter than the aggregate, and this is the rule that makes it so: a month the
 * forecast has closed for writing refuses a row even though the cash flow's own import window
 * accepts the date.
 *
 * <p><b>Task F38.</b> This rule shipped untested, and not by oversight - it was untestable. Its one
 * input came through {@code CashFlowServiceClient}, whose test double built month statuses from a
 * heuristic ("past months in OPEN mode are ROLLED_OVER") and said in a comment that it could not
 * produce {@code ATTESTED}. So the refusal below was unreachable in every test that existed.
 * {@link CashFlowForecastApi} has one method, which makes the input a one-line lambda - that is the
 * whole reason this test can exist now.
 *
 * <p>Every case keeps the date inside the import window on purpose. If the window refused it too,
 * the row would be invalid for a reason that has nothing to do with month statuses, and the test
 * would pass without exercising the rule it is named after.
 */
class StagingRefusesClosedMonthsTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-03-15T00:00:00Z[UTC]");

    private static final YearMonth JANUARY = YearMonth.of(2026, 1);
    private static final YearMonth FEBRUARY = YearMonth.of(2026, 2);
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    private final StagedTransactionRepository stagedTransactions = mock(StagedTransactionRepository.class);
    private final StagingSessionMongoRepository sessions = mock(StagingSessionMongoRepository.class);
    private final CategoryMappingRepository categoryMappings = mock(CategoryMappingRepository.class);
    private final PatternMappingRepository patternMappings = mock(PatternMappingRepository.class);
    private final CashFlowModuleApi cashFlowApi = mock(CashFlowModuleApi.class);
    private final UserFinancialProfileApi profiles = mock(UserFinancialProfileApi.class);

    @Test
    void anAttestedMonthIsRefusedWhileTheActiveOneIsAccepted() {
        List<StagedTransaction> staged = stage(
                Map.of(JANUARY, MonthStatus.IMPORTED,
                        FEBRUARY, MonthStatus.ATTESTED,
                        MARCH, MonthStatus.ACTIVE),
                transaction("TXN-FEB", "2026-02-10T12:00:00Z[UTC]"),
                transaction("TXN-MAR", "2026-03-10T12:00:00Z[UTC]"));

        assertThat(errorsOf(staged, "TXN-FEB"))
                .containsExactly("Cannot import to ATTESTED month 2026-02. This month is read-only.");
        assertThat(errorsOf(staged, "TXN-MAR"))
                .as("the same import window accepted both dates, so only the status can explain the refusal")
                .isEmpty();
    }

    @Test
    void aRolledOverMonthStillAcceptsGapFilling() {
        List<StagedTransaction> staged = stage(
                Map.of(FEBRUARY, MonthStatus.ROLLED_OVER, MARCH, MonthStatus.ACTIVE),
                transaction("TXN-FEB", "2026-02-10T12:00:00Z[UTC]"));

        assertThat(errorsOf(staged, "TXN-FEB")).isEmpty();
    }

    @Test
    void aMonthTheForecastDoesNotKnowIsRefusedAsOutsideItsRange() {
        List<StagedTransaction> staged = stage(
                Map.of(MARCH, MonthStatus.ACTIVE),
                transaction("TXN-FEB", "2026-02-10T12:00:00Z[UTC]"));

        assertThat(errorsOf(staged, "TXN-FEB"))
                .containsExactly("Month 2026-02 is outside the CashFlow forecast range (start: 2026-01).");
    }

    /**
     * The branch production reaches when the statement has not been built yet. It used to be the
     * branch production reached when the forecast's endpoint was merely unreachable, because the
     * HTTP client turned every failure into an empty map.
     */
    @Test
    void aCashFlowWithNoForecastYetRefusesEveryRow() {
        List<StagedTransaction> staged = stage(
                Map.of(),
                transaction("TXN-MAR", "2026-03-10T12:00:00Z[UTC]"));

        assertThat(errorsOf(staged, "TXN-MAR"))
                .containsExactly("Month 2026-03 is outside the CashFlow forecast range (start: 2026-01).");
    }

    private List<StagedTransaction> stage(
            Map<YearMonth, MonthStatus> monthStatuses,
            StageTransactionsCommand.BankTransaction... transactions) {

        when(cashFlowApi.alreadyImported(any())).thenReturn(ImportedTransactions.none());
        when(stagedTransactions.saveAll(any())).thenAnswer(call -> call.getArgument(0));

        CashFlowServiceClient cashFlowServiceClient = id -> openCashFlow();
        CashFlowForecastApi forecast = id -> new MonthStatuses(id, monthStatuses);

        StageTransactionsCommandHandler handler = new StageTransactionsCommandHandler(
                stagedTransactions,
                sessions,
                categoryMappings,
                patternMappings,
                cashFlowServiceClient,
                cashFlowApi,
                forecast,
                new BankDataIngestionConfig(),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
                profiles);

        handler.handle(new StageTransactionsCommand(CASH_FLOW_ID, List.of(transactions)));

        // The result carries breakdowns and counts, not the rows - so the rows are read where the
        // handler puts them.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StagedTransaction>> staged = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(stagedTransactions).saveAll(staged.capture());
        return staged.getValue();
    }

    /**
     * OPEN, running since January, with one category the transactions below match by name - so
     * every row reaches validation instead of stopping at PENDING_MAPPING.
     */
    private static CashFlowInfo openCashFlow() {
        return new CashFlowInfo(
                CASH_FLOW_ID.id(),
                "U10000001",
                CashFlowInfo.CashFlowStatus.OPEN,
                MARCH,
                JANUARY,
                List.of(),
                List.of(new CashFlowInfo.CategoryInfo("Zakupy", null, Type.OUTFLOW, false, List.of())),
                0);
    }

    private static StageTransactionsCommand.BankTransaction transaction(String id, String paidDate) {
        return new StageTransactionsCommand.BankTransaction(
                id,
                "Sklep",
                "Zakupy spożywcze",
                "Zakupy",
                Money.of(100.0, "PLN"),
                Type.OUTFLOW,
                ZonedDateTime.parse(paidDate),
                null,
                null,
                null,
                null,
                null);
    }

    private static List<String> errorsOf(List<StagedTransaction> staged, String bankTransactionId) {
        return staged.stream()
                .filter(row -> bankTransactionId.equals(row.originalData().bankTransactionId()))
                .findFirst()
                .map(row -> row.validation().errors())
                .orElseThrow(() -> new AssertionError("no staged transaction for " + bankTransactionId));
    }
}
