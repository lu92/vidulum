package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.Ticker;

public class AssetNotFoundException extends BusinessException {

    public AssetNotFoundException(Ticker ticker) {
        super(String.format("Cannot find asset with ticker [%s]", ticker.getId()));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_ASSET_NOT_FOUND;
    }
}
