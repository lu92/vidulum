package com.multi.vidulum.cashflow.api;

import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.error.CategoryAlreadyExistsException;
import com.multi.vidulum.cashflow.api.error.ImportDateBeforeStartPeriodException;
import com.multi.vidulum.cashflow.api.error.ImportDateInFutureException;
import com.multi.vidulum.cashflow.api.error.ImportDateOutsideSetupPeriodException;
import com.multi.vidulum.cashflow.api.error.ImportNotAllowedInClosedModeException;
import com.multi.vidulum.cashflow.api.error.ImportToForecastedMonthNotAllowedException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.ImportedTransactions;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.shared.module.ModuleApi;

import java.util.List;

/**
 * What another module may ask of cashflow.
 *
 * <p>Every method here earned its place at a real call site; none was added because a class happens
 * to expose it. The reads were derived
 * from {@code GetStagingPreviewQueryHandler}, which loads the whole aggregate through
 * {@code DomainCashFlowRepository} in order to read two fields off its snapshot - and from the six
 * places that went through {@code CashFlowServiceClient.getCashFlowInfo}, a port bank-data-ingestion
 * declared for itself, which between them read the owner, the lifecycle phase, both periods and the
 * categories, and nothing else out of a payload that also carried every transaction. That port is
 * gone: its readers are on {@link #skeletonOf} and {@code CashFlowForecastApi}, and nothing in this
 * application asks cashflow a question it declared the shape of itself.
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
     * uses for the same thing, so the two halves of this contract agree on what a root is. The
     * implementation translates it into whatever the domain spells "no parent" as; a caller must
     * not have to know that word, and when this translation was missing the forecast projection
     * died on the event with an NPE while every write still succeeded.
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

    /**
     * Discards everything an import put into a cash flow, and answers with how much that was.
     *
     * <p>Allowed only while the cash flow is in
     * {@link CashFlowSkeletonView.LifecyclePhase#SETUP} - once the owner has attested the import,
     * the history is theirs and this refuses. {@code Uncategorized} always survives; everything
     * else goes only if {@code deleteCategories} says so, which a caller decides from whether the
     * import created any categories in the first place.
     *
     * <p><b>The return value is the point of this method existing.</b> See {@link RollbackResult}
     * for what the three callers were doing to obtain those two numbers before - one of them
     * fabricating a zero, one subtracting across five requests, one skipping the work entirely.
     *
     * @throws CashFlowNotFoundException when no such cash flow exists
     */
    /**
     * Records transactions a consumer expects to happen, all of them or none, and answers with the
     * ids they were given - in the order they were handed over.
     *
     * <p><b>Batch only, on purpose.</b> The single-append endpoint exists for a person adding one
     * planned transaction in the UI; this contract serves a machine deriving many at once, and for
     * such a caller one-at-a-time was never the right shape. A daily recurring rule covers 365 days
     * of the forecast window, and the loop it replaces paid, per occurrence, a request, an aggregate
     * load, a whole-document save, a round trip to the id sequence and a message on the topic.
     *
     * <p><b>Atomic.</b> Every item is checked before any is appended, so a batch either lands whole
     * or changes nothing. This matters more than the cost: a loop that failed on occurrence 200 left
     * 199 cash changes in the cash flow while the caller recorded none of their ids, so nothing could
     * ever find them again.
     *
     * <p>{@code sourceRuleId} ties the batch to whatever derived it, which is how it can later be
     * withdrawn as a batch too.
     *
     * @param changes the transactions to record; empty appends nothing and answers empty
     * @return the ids minted, positionally matching {@code changes}
     * @throws CashFlowNotFoundException when no such cash flow exists
     */
    List<CashChangeId> appendExpected(CashFlowId cashFlowId, String sourceRuleId, List<ExpectedCashChange> changes);

    /**
     * Withdraws expected cash changes a consumer had recorded, by id, in one atomic operation.
     *
     * <p>The counterpart of {@link #appendExpected}, and the pair is the point: whatever derived a
     * batch can take it back, which is how a recurring rule gets edited or deleted without leaving
     * its old predictions in someone's forecast.
     *
     * <p><b>Skips rather than refuses, unlike appendExpected.</b> A cash change that is no longer
     * pending has been confirmed by the owner, so it is a fact about their money and not a
     * prediction any more; removing it would be destroying data the consumer does not own. It is
     * counted in {@link DeletedExpectedCashChanges#skipped()} and left where it is. An id that does
     * not exist is skipped for the same reason - there is nothing to destroy and nothing to report.
     *
     * @param cashChangeIds the ids to withdraw; empty withdraws nothing and answers two zeros
     * @throws CashFlowNotFoundException when no such cash flow exists
     */
    DeletedExpectedCashChanges deleteExpected(CashFlowId cashFlowId, String sourceRuleId, List<CashChangeId> cashChangeIds);

    RollbackResult rollbackImport(CashFlowId cashFlowId, boolean deleteCategories);

    /**
     * Everything this cash flow has taken in from a source system, indexed by that system's id.
     *
     * <p>What a consumer needs to tell a transaction it has not seen from one it imported last
     * week. Three call sites in bank-data-ingestion ask it, and the answer decides whether a staged
     * row is marked {@code DUPLICATE} - which also keeps it out of the import entirely, rather than
     * letting the aggregate refuse it silently later.
     *
     * <p>Until cashflow started storing {@code bankTransactionId} there was nothing to answer with:
     * the HTTP client returned an empty set with a comment saying the API might report these one
     * day, and the test double filled it with {@code CashChangeId}s from a different namespace - so
     * the duplicate check compared against nothing and could never fire.
     *
     * <p><b>A separate method rather than a field on the skeleton, deliberately.</b>
     * {@link #skeletonOf} answers identically for a cash flow with four years of history and one
     * created a minute ago, which is the property that makes it cheap enough to fetch for a single
     * boolean. Four hundred entries would end that, and every caller that only wants the lifecycle
     * phase would pay for them.
     *
     * @throws CashFlowNotFoundException when no such cash flow exists
     * @see ImportedTransactions for why this is an indexed type and not a {@code Set}
     */
    ImportedTransactions alreadyImported(CashFlowId cashFlowId);
}
