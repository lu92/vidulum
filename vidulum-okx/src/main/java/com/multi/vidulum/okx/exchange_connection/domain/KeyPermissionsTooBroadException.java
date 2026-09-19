package com.multi.vidulum.okx.exchange_connection.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

import java.util.Set;

/**
 * The key reported permissions beyond {@code read_only}. Vidulum never places orders and never
 * moves funds, so a key that could do either is refused rather than merely unused — the
 * non-negotiable condition from {@code OKX-CONTEXT.md}.
 *
 * <p>The message names what was reported so the user can see which permission to remove when
 * reissuing the key.
 */
public class KeyPermissionsTooBroadException extends BusinessException {

    public KeyPermissionsTooBroadException(String raw, Set<String> parsed) {
        super("API key reports permissions [" + String.join(", ", parsed) + "] (raw: \"" + raw
                + "\"); Vidulum accepts \"" + ReportedKeyPermissions.READ_ONLY + "\" only");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.EXCHANGE_KEY_PERMISSIONS_TOO_BROAD;
    }
}
