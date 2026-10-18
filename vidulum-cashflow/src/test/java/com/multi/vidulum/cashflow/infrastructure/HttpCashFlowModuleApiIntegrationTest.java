package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.cashflow.CashFlowIntegrationTest;
import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.HistoricalCashChange;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.error.CategoryAlreadyExistsException;
import com.multi.vidulum.cashflow.api.error.ImportDateOutsideSetupPeriodException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.app.CashFlowHttpActor;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP transport of {@link CashFlowModuleApi}, against a running server.
 *
 * <p><b>What this exists to prove.</b> The contract claims the transport is not part of it. That
 * claim is only worth something if a second implementation actually answers the same three methods
 * the same way, so this drives {@link HttpCashFlowModuleApi} over real HTTP - real JWT, real
 * controller, real Mongo - and asserts the answers, not the wiring.
 *
 * <p>Two things here are the whole reason the shape changed:
 *
 * <ul>
 *   <li><b>The failures survive the trip.</b> A duplicate category arrives as
 *       {@link CategoryAlreadyExistsException} and an unknown cash flow as
 *       {@link CashFlowNotFoundException} - the same types the in-process implementation throws,
 *       reconstructed from the status code the server's {@code ErrorCode} produced. The port this
 *       replaces matched on {@code responseBody.contains("already exists")}.</li>
 *   <li><b>{@code isImport} does something.</b> {@code false} against a cash flow in SETUP is
 *       refused and {@code true} is allowed, which is what makes it worth a parameter. Before, the
 *       HTTP client pinned {@code ?isImport=true} into its URL, so this distinction could not be
 *       expressed, let alone tested.</li>
 * </ul>
 *
 * <p><b>Where the two transports are not identical, and this test says so.</b> The contract
 * declares two failures for {@code createCategory} and both survive the wire. A third refusal does
 * not: a cash flow in SETUP rejecting a user-initiated category raises
 * {@code OperationNotAllowedInSetupModeException} in-process and arrives here as a plain
 * {@code HttpClientErrorException.BadRequest}, because that exception lives in cashflow's domain
 * rather than in the contract. {@code shouldHonourIsImportFlagOverHttp} asserts the leak by name
 * instead of hiding it behind {@code RuntimeException}, so the gap is visible and will fail loudly
 * the day that exception moves to {@code cashflow/api/error} and the mapping should be added here.
 *
 * <p>Note what the test does <i>not</i> have to do: there is no {@code setJwtToken} hook on the
 * adapter. It takes a configured {@code RestClient}, so a test supplies credentials the same way
 * production does - through the client - instead of the class carrying a test-only setter.
 */
class HttpCashFlowModuleApiIntegrationTest extends CashFlowIntegrationTest {

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

    private CashFlowModuleApi httpApi;
    private CashFlowHttpActor actor;

    @BeforeEach
    void setUp() {
        registerAndAuthenticate();

        httpApi = new HttpCashFlowModuleApi(RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + accessToken)
                .build());

        actor = new CashFlowHttpActor(restTemplate, port);
        actor.setJwtToken(accessToken);
    }

    private String uniqueName() {
        return "HttpModuleApi-" + NAME_COUNTER.incrementAndGet();
    }

    @Test
    @DisplayName("skeletonOf over HTTP answers what the in-process implementation answers")
    void shouldReadSkeletonOverHttp() {
        CashFlowId cashFlowId = CashFlowId.of(actor.createCashFlow(userId, uniqueName(), "GBP"));

        CashFlowSkeletonView skeleton = httpApi.skeletonOf(cashFlowId);

        assertThat(skeleton.cashFlowId()).isEqualTo(cashFlowId);
        assertThat(skeleton.owner()).isEqualTo(UserId.of(userId));
        assertThat(skeleton.phase()).isEqualTo(CashFlowSkeletonView.LifecyclePhase.OPEN);
        assertThat(skeleton.activePeriod()).isEqualTo(YearMonth.of(2022, 1));
        assertThat(skeleton.startPeriod()).isEqualTo(YearMonth.of(2022, 1));
        assertThat(skeleton.allCategoryNames())
                .as("the default structure a new cash flow is born with")
                .contains(new CategoryName("Uncategorized"));
    }

    @Test
    @DisplayName("skeletonOf over HTTP reports SETUP for a cash flow created with history")
    void shouldReportSetupPhaseOverHttp() {
        CashFlowId cashFlowId = CashFlowId.of(actor.createCashFlowWithHistory(
                userId, uniqueName(), YearMonth.of(2021, 10), Money.of(10000.0, "GBP")));

        CashFlowSkeletonView skeleton = httpApi.skeletonOf(cashFlowId);

        assertThat(skeleton.phase()).isEqualTo(CashFlowSkeletonView.LifecyclePhase.SETUP);
        assertThat(skeleton.isInSetup()).isTrue();
        assertThat(skeleton.startPeriod()).isEqualTo(YearMonth.of(2021, 10));
        assertThat(skeleton.activePeriod()).isEqualTo(YearMonth.of(2022, 1));
    }

    @Test
    @DisplayName("skeletonOf over HTTP raises the contract's own failure for an unknown cash flow")
    void shouldRaiseNotFoundOverHttp() {
        CashFlowId unknown = CashFlowId.of("CF99999999");

        assertThatThrownBy(() -> httpApi.skeletonOf(unknown))
                .isInstanceOf(CashFlowNotFoundException.class)
                .hasMessageContaining("CF99999999");
    }

    @Test
    @DisplayName("exists over HTTP answers both ways without manufacturing a boolean from a 404")
    void shouldAnswerExistsOverHttp() {
        CashFlowId existing = CashFlowId.of(actor.createCashFlow(userId, uniqueName(), "GBP"));

        assertThat(httpApi.exists(existing)).isTrue();
        assertThat(httpApi.exists(CashFlowId.of("CF99999999"))).isFalse();
    }

    @Test
    @DisplayName("createCategory over HTTP creates a top-level category and a child under it")
    void shouldCreateCategoriesOverHttp() {
        CashFlowId cashFlowId = CashFlowId.of(actor.createCashFlow(userId, uniqueName(), "GBP"));

        httpApi.createCategory(cashFlowId, new CategoryName("Salary"), null, Type.INFLOW, false);
        httpApi.createCategory(cashFlowId, new CategoryName("Bonus"), new CategoryName("Salary"), Type.INFLOW, false);

        CashFlowSkeletonView skeleton = httpApi.skeletonOf(cashFlowId);
        assertThat(skeleton.inflowCategoryNames())
                .as("flattened, so a child counts")
                .contains(new CategoryName("Salary"), new CategoryName("Bonus"));
        assertThat(skeleton.inflowCategories())
                .filteredOn(node -> node.name().equals(new CategoryName("Salary")))
                .singleElement()
                .satisfies(salary -> {
                    assertThat(salary.isRoot()).as("null parent means top-level").isTrue();
                    assertThat(salary.subCategories())
                            .singleElement()
                            .satisfies(bonus -> {
                                assertThat(bonus.name()).isEqualTo(new CategoryName("Bonus"));
                                assertThat(bonus.parentName()).isEqualTo(new CategoryName("Salary"));
                            });
                });
    }

    @Test
    @DisplayName("createCategory over HTTP raises CategoryAlreadyExistsException, not a parsed string")
    void shouldRaiseAlreadyExistsOverHttp() {
        CashFlowId cashFlowId = CashFlowId.of(actor.createCashFlow(userId, uniqueName(), "GBP"));
        httpApi.createCategory(cashFlowId, new CategoryName("Salary"), null, Type.INFLOW, false);

        assertThatThrownBy(() ->
                httpApi.createCategory(cashFlowId, new CategoryName("Salary"), null, Type.INFLOW, false))
                .isInstanceOf(CategoryAlreadyExistsException.class)
                .hasMessageContaining("Salary");
    }

    @Test
    @DisplayName("isImport decides whether a cash flow in SETUP accepts a category at all")
    void shouldHonourIsImportFlagOverHttp() {
        CashFlowId inSetup = CashFlowId.of(actor.createCashFlowWithHistory(
                userId, uniqueName(), YearMonth.of(2021, 10), Money.of(10000.0, "GBP")));
        assertThat(httpApi.skeletonOf(inSetup).isInSetup()).isTrue();

        assertThatThrownBy(() ->
                httpApi.createCategory(inSetup, new CategoryName("Groceries"), null, Type.OUTFLOW, false))
                .as("a user-initiated category on a cash flow still importing its history is refused")
                .isInstanceOf(HttpClientErrorException.BadRequest.class);

        httpApi.createCategory(inSetup, new CategoryName("Groceries"), null, Type.OUTFLOW, true);

        assertThat(httpApi.skeletonOf(inSetup).outflowCategoryNames())
                .as("the same call with isImport=true is allowed")
                .contains(new CategoryName("Groceries"));
    }

    @Test
    @DisplayName("importHistorical over HTTP keeps the amount exact and the time of day intact")
    void shouldImportHistoricalOverHttpWithoutLosingPrecision() {
        CashFlowId cashFlowId = CashFlowId.of(actor.createCashFlowWithHistory(
                userId, uniqueName(), YearMonth.of(2021, 10), Money.of(10000.0, "GBP")));
        httpApi.createCategory(cashFlowId, new CategoryName("Groceries"), null, Type.OUTFLOW, true);

        // A value double cannot hold exactly, and a time of day with a zone that is not the
        // server's. The port this replaces sent amount.doubleValue() and a LocalDate, then rebuilt
        // midnight in ZoneId.systemDefault() - so both of these assertions would have failed.
        Money exact = Money.of(new BigDecimal("1234.57"), "GBP");
        ZonedDateTime paidAt = ZonedDateTime.parse("2021-11-14T13:45:30+01:00[Europe/Warsaw]");

        CashChangeId cashChangeId = httpApi.importHistorical(cashFlowId, new HistoricalCashChange(
                new CategoryName("Groceries"), "Tesco", "weekly shop",
                exact, Type.OUTFLOW, paidAt, paidAt, false, "NEST-2021-11-14-001"));

        assertThat(cashChangeId).isNotNull();
        assertThat(cashChangeId.id()).startsWith("CC");

        CashChangeSnapshot stored = domainCashFlowRepository.findById(cashFlowId)
                .orElseThrow()
                .getSnapshot()
                .cashChanges()
                .get(cashChangeId);

        assertThat(stored).as("the cash change the server actually persisted").isNotNull();
        assertThat(stored.money().getAmount())
                .as("exact to the penny after a real JSON round trip")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("1234.57"));
        assertThat(stored.money().getCurrency()).isEqualTo("GBP");
        // The aggregate stores paidDate in endDate - CashChangeSnapshot has no paidDate field of
        // its own, which is worth knowing before reading any of this code.
        assertThat(stored.endDate().toInstant())
                .as("13:45:30+01:00, not midnight in whichever zone the server runs in")
                .isEqualTo(paidAt.toInstant());
        assertThat(stored.dueDate().toInstant())
                .as("and dueDate holds the same instant, because the only caller sends paidDate "
                        + "for both - a known duplicate, left alone deliberately")
                .isEqualTo(paidAt.toInstant());
        assertThat(stored.categoryName()).isEqualTo(new CategoryName("Groceries"));
    }

    @Test
    @DisplayName("importing the same bank transaction twice records it once and returns the same id")
    void shouldBeIdempotentOnTheBanksOwnTransactionId() {
        CashFlowId cashFlowId = CashFlowId.of(actor.createCashFlowWithHistory(
                userId, uniqueName(), YearMonth.of(2021, 10), Money.of(10000.0, "GBP")));
        httpApi.createCategory(cashFlowId, new CategoryName("Groceries"), null, Type.OUTFLOW, true);

        HistoricalCashChange sameRowTwice = new HistoricalCashChange(
                new CategoryName("Groceries"), "Tesco", "weekly shop",
                Money.of(new BigDecimal("99.99"), "GBP"), Type.OUTFLOW,
                ZonedDateTime.parse("2021-11-14T13:45:30Z[UTC]"),
                ZonedDateTime.parse("2021-11-14T13:45:30Z[UTC]"),
                false,
                "NEST-2021-11-14-DUPLICATE");

        CashChangeId first = httpApi.importHistorical(cashFlowId, sameRowTwice);
        CashChangeId second = httpApi.importHistorical(cashFlowId, sameRowTwice);

        assertThat(second)
                .as("the id issued the first time, not a second cash change")
                .isEqualTo(first);

        Map<CashChangeId, CashChangeSnapshot> stored = domainCashFlowRepository.findById(cashFlowId)
                .orElseThrow()
                .getSnapshot()
                .cashChanges();

        assertThat(stored)
                .as("one transaction in the cash flow, not two - this is what re-uploading the "
                        + "same statement used to do 402 times over")
                .hasSize(1);
        assertThat(stored.get(first).bankTransactionId()).isEqualTo("NEST-2021-11-14-DUPLICATE");
    }

    @Test
    @DisplayName("two different bank transactions with identical contents are both recorded")
    void shouldNotConfuseTwoGenuinelyDifferentTransactions() {
        CashFlowId cashFlowId = CashFlowId.of(actor.createCashFlowWithHistory(
                userId, uniqueName(), YearMonth.of(2021, 10), Money.of(10000.0, "GBP")));
        httpApi.createCategory(cashFlowId, new CategoryName("Groceries"), null, Type.OUTFLOW, true);

        // Same shop, same amount, same day - two real visits. Only the bank's id tells them apart,
        // which is why deduplicating on contents would have been the wrong design.
        ZonedDateTime sameDay = ZonedDateTime.parse("2021-11-14T13:45:30Z[UTC]");
        CashChangeId morning = httpApi.importHistorical(cashFlowId, new HistoricalCashChange(
                new CategoryName("Groceries"), "Tesco", "weekly shop",
                Money.of(new BigDecimal("99.99"), "GBP"), Type.OUTFLOW, sameDay, sameDay, false,
                "NEST-VISIT-1"));
        CashChangeId afternoon = httpApi.importHistorical(cashFlowId, new HistoricalCashChange(
                new CategoryName("Groceries"), "Tesco", "weekly shop",
                Money.of(new BigDecimal("99.99"), "GBP"), Type.OUTFLOW, sameDay, sameDay, false,
                "NEST-VISIT-2"));

        assertThat(afternoon).isNotEqualTo(morning);
        assertThat(domainCashFlowRepository.findById(cashFlowId).orElseThrow()
                .getSnapshot().cashChanges())
                .hasSize(2);
    }

    @Test
    @DisplayName("a transaction with no source id is never treated as a duplicate")
    void shouldNotMatchOnAbsentSourceId() {
        CashFlowId cashFlowId = CashFlowId.of(actor.createCashFlowWithHistory(
                userId, uniqueName(), YearMonth.of(2021, 10), Money.of(10000.0, "GBP")));
        httpApi.createCategory(cashFlowId, new CategoryName("Groceries"), null, Type.OUTFLOW, true);

        ZonedDateTime paidAt = ZonedDateTime.parse("2021-11-14T13:45:30Z[UTC]");
        HistoricalCashChange noSourceId = new HistoricalCashChange(
                new CategoryName("Groceries"), "cash", "no receipt",
                Money.of(20.0, "GBP"), Type.OUTFLOW, paidAt, paidAt, false, null);

        CashChangeId first = httpApi.importHistorical(cashFlowId, noSourceId);
        CashChangeId second = httpApi.importHistorical(cashFlowId, noSourceId);

        assertThat(second)
                .as("null must match nothing - silently collapsing all unsourced transactions "
                        + "into one would be far worse than recording two")
                .isNotEqualTo(first);
        assertThat(domainCashFlowRepository.findById(cashFlowId).orElseThrow()
                .getSnapshot().cashChanges())
                .hasSize(2);
    }

    @Test
    @DisplayName("importHistorical over HTTP refuses a date the import window already rejects")
    void shouldRefuseADateOutsideTheImportWindowOverHttp() {
        CashFlowId cashFlowId = CashFlowId.of(actor.createCashFlowWithHistory(
                userId, uniqueName(), YearMonth.of(2021, 10), Money.of(10000.0, "GBP")));
        httpApi.createCategory(cashFlowId, new CategoryName("Groceries"), null, Type.OUTFLOW, true);

        // The clock is fixed at 2022-01-01, so the cash flow's activePeriod is 2022-01 and a
        // transaction dated in it is not historical. The window says so without a request:
        ZonedDateTime notHistorical = ZonedDateTime.parse("2022-01-05T10:00:00Z[UTC]");
        assertThat(httpApi.skeletonOf(cashFlowId).importWindow()
                .refusalFor(notHistorical, ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]")))
                .as("answered locally, from the skeleton, before anything is attempted")
                .get()
                .isInstanceOf(ImportDateOutsideSetupPeriodException.class);

        // And the write refuses it too, because a contract does not trust the caller to have asked.
        assertThatThrownBy(() -> httpApi.importHistorical(cashFlowId, new HistoricalCashChange(
                new CategoryName("Groceries"), "Tesco", "too recent",
                Money.of(10.0, "GBP"), Type.OUTFLOW, notHistorical, notHistorical, false, "NEST-X")))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("createCategory over HTTP raises the contract's failure for an unknown cash flow")
    void shouldRaiseNotFoundWhenCreatingOverHttp() {
        assertThatThrownBy(() -> httpApi.createCategory(
                CashFlowId.of("CF99999999"), new CategoryName("Salary"), null, Type.INFLOW, true))
                .isInstanceOf(CashFlowNotFoundException.class);
    }
}
