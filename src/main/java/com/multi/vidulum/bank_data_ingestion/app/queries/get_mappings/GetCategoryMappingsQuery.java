package com.multi.vidulum.bank_data_ingestion.app.queries.get_mappings;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.queries.Query;

/**
 * Query to retrieve all category mappings for a given CashFlow.
 *
 * @param cashFlowId the CashFlow to get mappings for
 */
public record GetCategoryMappingsQuery(
        CashFlowId cashFlowId
) implements Query {
}
