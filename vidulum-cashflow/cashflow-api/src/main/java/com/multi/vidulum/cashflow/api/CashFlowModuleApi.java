package com.multi.vidulum.cashflow.api;

import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.shared.module.ModuleApi;

/**
 * What another module may ask of cashflow.
 *
 * <p>One method, because one method is what the call sites justify today. The contract was derived
 * from {@code GetStagingPreviewQueryHandler}, which loads the whole aggregate through
 * {@code DomainCashFlowRepository} in order to read two fields off its snapshot - and from the six
 * places that go through {@code CashFlowServiceClient.getCashFlowInfo}, which between them read the
 * owner, the lifecycle phase, both periods and the categories, and nothing else out of a payload
 * that also carries every transaction.
 *
 * <p>The transport is not part of this. Today the implementation answers in-process; when
 * bank-data-ingestion leaves this artifact it will answer over HTTP through an adapter, and not a
 * line here changes. That separation is the reason this is a plain interface with no Spring and no
 * {@code @HttpExchange} - see {@code PortfolioModuleApi} for the pattern.
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
}
