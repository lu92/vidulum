package com.multi.vidulum.bank_data_ingestion.app.commands.ai_categorize;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to trigger AI-powered categorization for a staging session.
 *
 * This command analyzes staged transactions and returns AI-powered
 * category structure and pattern mapping suggestions.
 *
 * @param cashFlowId the CashFlow ID
 * @param sessionId  the staging session ID to categorize
 * @param userId     the user ID (for user-specific pattern cache)
 */
public record AiCategorizeCommand(
        CashFlowId cashFlowId,
        StagingSessionId sessionId,
        String userId
) implements Command {
}
