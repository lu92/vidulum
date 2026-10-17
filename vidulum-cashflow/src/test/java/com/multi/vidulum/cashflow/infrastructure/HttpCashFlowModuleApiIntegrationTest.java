package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.cashflow.CashFlowIntegrationTest;
import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.error.CategoryAlreadyExistsException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.app.CashFlowHttpActor;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.YearMonth;
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
    @DisplayName("createCategory over HTTP raises the contract's failure for an unknown cash flow")
    void shouldRaiseNotFoundWhenCreatingOverHttp() {
        assertThatThrownBy(() -> httpApi.createCategory(
                CashFlowId.of("CF99999999"), new CategoryName("Salary"), null, Type.INFLOW, true))
                .isInstanceOf(CashFlowNotFoundException.class);
    }
}
