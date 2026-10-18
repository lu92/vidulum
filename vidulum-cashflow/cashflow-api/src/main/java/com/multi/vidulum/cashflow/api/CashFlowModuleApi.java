package com.multi.vidulum.cashflow.api;

import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.error.CategoryAlreadyExistsException;
import com.multi.vidulum.cashflow.api.error.ImportDateBeforeStartPeriodException;
import com.multi.vidulum.cashflow.api.error.ImportDateInFutureException;
import com.multi.vidulum.cashflow.api.error.ImportDateOutsideSetupPeriodException;
import com.multi.vidulum.cashflow.api.error.ImportNotAllowedInClosedModeException;
import com.multi.vidulum.cashflow.api.error.ImportToForecastedMonthNotAllowedException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.shared.module.ModuleApi;

/**
 * What another module may ask of cashflow.
 *
 * <p>Every method here earned its place at a real call site; none was added because a class happens
 * to expose it. The reads were derived
 * from {@code GetStagingPreviewQueryHandler}, which loads the whole aggregate through
 * {@code DomainCashFlowRepository} in order to read two fields off its snapshot - and from the six
 * places that go through {@code CashFlowServiceClient.getCashFlowInfo}, which between them read the
 * owner, the lifecycle phase, both periods and the categories, and nothing else out of a payload
 * that also carries every transaction.
 *
 * <p><b>The transport is not part of this, and that is now demonstrated rather than promised.</b>
 * Two implementations answer this interface and exactly one is a bean at a time:
 * {@code CashFlowModuleApiImpl} in-process through the command and query path, and
 * {@code HttpCashFlowModuleApi} over REST against the same endpoints a browser calls - selected by
 * {@code vidulum.cashflow.api.transport}, which defaults to {@code in-process}. Neither appears
 * here, which is why this is a plain interface with no Spring and no {@code @HttpExchange}; see
 * {@code PortfolioModuleApi} for the pattern.
 *
 * @see <a href="file:../../../../../../../docs/features-backlog/2026-10-16-cashflow-module-boundaries.md">the analysis</a>
 */
public interface CashFlowModuleApi extends ModuleApi {

    /**
     * The skeleton of a cash flow: who owns it, which period it is in, and how it organises money
     * into categories.
     *
     * <p><b>No transactions.</b> A cash flow with four years of history answers this with the same
     * payload as one created a minute ago - which is the whole point, and which
     * {@code CashFlowSkeletonViewComponentTest} asserts rather than trusts.
     *
     * @throws CashFlowNotFoundException when no such cash flow exists
     */
    CashFlowSkeletonView skeletonOf(CashFlowId cashFlowId);

    /**
     * Whether there is such a cash flow at all.
     *
     * <p>A second method for a question three call sites ask on its own: two of them configure
     * mappings or accept AI suggestions and never look at the cash flow's contents, so making them
     * fetch the skeleton to find out it exists would be asking for data in order to throw it away.
     *
     * <p>They used to ask the HTTP port, where {@code exists} was
     * {@code try { getCashFlowInfo(); return true; } catch { return false; }} — a boolean
     * manufactured from an exception at the cost of two requests and a payload carrying every
     * transaction.
     */
    boolean exists(CashFlowId cashFlowId);

    /**
     * Adds a category to a cash flow.
     *
     * <p>The contract's first <b>write</b>, and the reason it is here is that four call sites in
     * bank-data-ingestion were reaching for it through a port they had declared themselves: the
     * import job creating the categories a CSV implies, {@code force-uncategorized} creating
     * {@code Uncategorized} for each direction that needs it, and {@code accept-ai} creating the
     * ones a model proposed.
     *
     * <p><b>{@code parentCategoryName} is nullable, and {@code null} means a top-level category</b>
     * - the same encoding {@link com.multi.vidulum.cashflow.api.view.CategoryNodeView#isRoot()}
     * uses for the same thing, so the two halves of this contract agree on what a root is.
     *
     * <p><b>On {@code isImport}.</b> It says this category is being created as part of importing
     * history, which is the one circumstance under which a cash flow still in
     * {@link com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView.LifecyclePhase#SETUP} accepts
     * a new category at all; a user-initiated creation on a cash flow in SETUP is refused. It is a
     * parameter rather than two methods or a hardcoded default because it is a property of the
     * request, and the alternative is what the code did before: the HTTP client pinned
     * {@code ?isImport=true} into its URL, so the one caller that was <i>not</i> importing got
     * import permissions silently and no call site said which it was. A flag that changes what the
     * aggregate allows should be visible at the call site.
     *
     * @throws CashFlowNotFoundException      when no such cash flow exists
     * @throws CategoryAlreadyExistsException when an active category already holds that name
     */
    void createCategory(CashFlowId cashFlowId,
                        CategoryName categoryName,
                        CategoryName parentCategoryName,
                        Type type,
                        boolean isImport);

    /**
     * Records a transaction that already happened, and answers with the id it was given.
     *
     * <p>The other half of what an import does, after {@link #createCategory} has made the
     * categories a statement implies. One call site: the import job's second phase, looping over
     * the rows that passed staging.
     *
     * <p><b>Whether a date will be accepted is answerable before calling this</b>, and cheaply -
     * {@link CashFlowSkeletonView#importWindow()} carries the rule, so a consumer validates a whole
     * statement against one fetched skeleton rather than discovering refusals one write at a time.
     * This method still enforces it, because a contract may not trust a caller to have asked; the
     * window exists so the caller need not fail to find out.
     *
     * <p><b>Not idempotent, and that is a real gap rather than an omission here.</b> Calling it
     * twice with the same transaction records it twice, because cashflow stores no identifier from
     * the source system - a bank's transaction id has nowhere to go in {@code CashChangeSnapshot},
     * so there is nothing to deduplicate on. Until that changes, re-running an import duplicates
     * it. The fix belongs in cashflow (a stored key plus a unique index, as
     * {@code originTradeId} does for trades), not in this signature.
     *
     * @return the id of the recorded cash change
     * @throws CashFlowNotFoundException                 when no such cash flow exists
     * @throws ImportNotAllowedInClosedModeException     when the cash flow is closed
     * @throws ImportDateOutsideSetupPeriodException     in SETUP, when the date is not historical
     * @throws ImportToForecastedMonthNotAllowedException in OPEN, when the date is in a future month
     * @throws ImportDateBeforeStartPeriodException      when the date precedes the cash flow
     * @throws ImportDateInFutureException               when the date has not happened yet
     */
    CashChangeId importHistorical(CashFlowId cashFlowId, HistoricalCashChange change);
}
