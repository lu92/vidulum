package com.multi.vidulum.cashflow.app.commands.attesthistoricalimport;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to attest a historical import, transitioning CashFlow from SETUP to OPEN mode.
 * This marks the end of the historical import process and confirms the balance.
 *
 * @param cashFlowId           the cash flow to attest (must be in SETUP mode)
 * @param confirmedBalance     the user-confirmed current balance (for validation)
 * @param forceAttestation     if true, attest even if calculated balance differs from confirmed
 * @param createAdjustment     if true and balance differs, create an adjustment transaction
 */
public record AttestHistoricalImportCommand(
        CashFlowId cashFlowId,
        Money confirmedBalance,
        boolean forceAttestation,
        boolean createAdjustment
) implements Command {
}
