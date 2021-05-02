package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.Ticker;

public class AssetNotFoundException extends RuntimeException {

    public AssetNotFoundException(Ticker ticker) {
        super(String.format("Cannot find asset with ticker [%s]", ticker.getId()));
    }
}
