package com.multi.vidulum.cashflow.api.forecast;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.shared.module.ModuleApi;

/**
 * What another module may ask of a cash flow's <b>forecast</b>.
 *
 * <p>A second contract next to {@link com.multi.vidulum.cashflow.api.CashFlowModuleApi}, in the
 * same artifact but answered by a different subdomain, and that is the whole point of it existing
 * separately. Month statuses belong to {@code cashflow_forecast_processor}; the aggregate does not
 * know them. Had this been a method on {@code CashFlowModuleApi}, its implementation - which sits
 * in {@code cashflow.app} and holds the domain repository - would have had to reach for the
 * forecast's repository, which would be the first import from {@code cashflow} into
 * {@code cashflow_forecast_processor}. There is currently no such edge, and this keeps it that way:
 * two contracts, two implementations, one per subdomain.
 *
 * <p>What it replaced is worth recording. The one consumer, {@code bank-data-ingestion}, used to
 * ask for month statuses through its own port, whose only production implementation was HTTP - so
 * in a single JVM, staging a statement made the application call <i>its own</i>
 * {@code /cash-flow-forecast/cf={id}/month-statuses} endpoint over loopback. That port's one
 * remaining method had also been merging two modules' answers behind one signature, doing two
 * requests underneath.
 *
 * <p>In-process only, with no transport property. {@code CashFlowModuleApi} has two
 * implementations because something asked for the choice; nothing has asked here, and defaulting
 * an HTTP implementation on is exactly the mistake above.
 */
public interface CashFlowForecastApi extends ModuleApi {

    /**
     * Every month this cash flow's forecast knows about, with the status of each.
     *
     * <p>One request per upload, not per row - see {@link MonthStatuses}. Answers
     * {@link MonthStatuses#none} when no statement exists yet, because the read model is built
     * asynchronously and a cash flow may legitimately be ahead of it.
     */
    MonthStatuses monthStatusesOf(CashFlowId cashFlowId);
}
