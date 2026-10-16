package com.multi.vidulum.cashflow.api.error;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Asked about a cash flow that does not exist.
 *
 * <p>The contract's own failure, so that a consumer does not have to import cashflow's domain to
 * catch it - which is what {@code bank_data_ingestion} does today in five places, constructing and
 * throwing {@code CashFlowDoesNotExistsException} from another module's domain package.
 *
 * <p>Carries {@link ErrorCode#CASHFLOW_NOT_FOUND}, the same code the domain exception carries.
 * {@code ErrorHttpHandler} maps {@link BusinessException} generically and takes the status from the
 * code, so an endpoint behind this answers exactly as it did before.
 */
public class CashFlowNotFoundException extends BusinessException {

    private final CashFlowId cashFlowId;

    public CashFlowNotFoundException(CashFlowId cashFlowId) {
        super(String.format("Cash flow [%s] does not exists", cashFlowId));
        this.cashFlowId = cashFlowId;
    }

    public CashFlowId cashFlowId() {
        return cashFlowId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CASHFLOW_NOT_FOUND;
    }
}
