package com.multi.vidulum.pnl.app;

import com.multi.vidulum.common.UserId;

public class PnlHistoryNotFoundException extends RuntimeException {
    public PnlHistoryNotFoundException(UserId userId) {
        super(String.format("Pnl-history not found for user [%s]", userId));
    }
}
