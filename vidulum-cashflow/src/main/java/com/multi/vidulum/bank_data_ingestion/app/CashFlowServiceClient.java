package com.multi.vidulum.bank_data_ingestion.app;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.cashflow.api.Type;

import java.time.LocalDate;

/**
 * What is left of bank-data-ingestion's own view of cashflow.
 *
 * <p><b>This interface is shrinking on purpose, and what it loses goes to
 * {@link com.multi.vidulum.cashflow.api.CashFlowModuleApi}.</b> The difference between the two is
 * not the transport - it is who decides the shape. A port declared here means <i>this module</i>
 * wrote down what cashflow's API looks like, so cashflow cannot change its own payload without
 * breaking a consumer it cannot see. The contract is the other way round: cashflow states what may
 * be asked of it and ships both an in-process and an HTTP implementation.
 *
 * <p><b>One method left.</b> {@code exists}, {@code createCategory}, {@code importHistorical} and
 * {@code rollbackImport} have all moved to the contract. What remains is the hardest one, and not
 * because of its size: {@code getCashFlowInfo} merges cashflow's own state with the <i>forecast</i>
 * module's month statuses behind one signature, doing two requests underneath. Four of its five
 * call sites now need only things {@code CashFlowSkeletonView} already carries; the fifth wants
 * month statuses, which belong to {@code cashflow_forecast_processor} and are therefore an argument
 * for a second contract, not a method on this one. See
 * {@code docs/features-backlog/2026-10-16-cashflow-module-boundaries.md}.
 */
public interface CashFlowServiceClient {

    /**
     * Get CashFlow information needed for staging and import operations.
     *
     * @param cashFlowId the CashFlow ID
     * @return CashFlowInfo with status, categories, periods, etc.
     * @throws CashFlowNotFoundException if CashFlow does not exist
     */
    CashFlowInfo getCashFlowInfo(String cashFlowId);



    // ============ Request/Response DTOs ============


    // ============ Exceptions ============

    // "No such cash flow" is not declared here any more: it belongs to cashflow, so it is
    // CashFlowNotFoundException from cashflow-api, which carries an ErrorCode and therefore
    // answers 404. The version that used to live here extended RuntimeException with no code, so
    // every call site had to catch it and translate - and any site that forgot answered 500.

    // "Category already exists" is not declared here any more either, for the same reason as
    // "no such cash flow": it is cashflow's failure, so it is CategoryAlreadyExistsException from
    // cashflow-api, carrying ErrorCode.CATEGORY_ALREADY_EXISTS and therefore answering 409. And
    // creating a category is no longer this port's business at all - CashFlowModuleApi.createCategory
    // is, which is why the method above is gone.

    // ImportFailedException is gone with the method that raised it. It was the shape that made
    // the five typed date refusals unusable: a RuntimeException carrying e.getMessage(), so a
    // caller could not tell "date in the future" from "cash flow is closed" without reading prose.
    // CashFlowModuleApi.importHistorical declares all five, and CashFlowSkeletonView.importWindow()
    // hands the caller the same typed refusal before it even attempts the write.
}
