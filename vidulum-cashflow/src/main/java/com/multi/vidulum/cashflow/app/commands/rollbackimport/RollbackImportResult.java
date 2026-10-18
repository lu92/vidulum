package com.multi.vidulum.cashflow.app.commands.rollbackimport;

import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.common.CashFlowId;

/**
 * What {@link RollbackImportCommandHandler} answers with.
 *
 * <p>It used to return {@code CashFlowSnapshot}, which is the one thing that cannot describe a
 * rollback: taken after the fact, its {@code cashChanges} is empty, so the two counts the handler
 * had just computed were gone by the time anybody could read them. Both the endpoint and
 * bank-data-ingestion then reinvented them - badly. This record carries them out instead.
 *
 * <p>{@code status} comes along because the endpoint reports it and the aggregate is the only thing
 * that knows it; a rollback leaves the cash flow in SETUP, ready for another attempt.
 */
public record RollbackImportResult(
        CashFlowId cashFlowId,
        int transactionsDeleted,
        int categoriesDeleted,
        CashFlow.CashFlowStatus status) {
}
