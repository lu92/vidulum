package com.multi.vidulum.exchange_connection.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * No permissions were reported at all. Distinct from {@link KeyPermissionsTooBroadException}:
 * this is a malformed request, not a key we refuse.
 */
public class KeyPermissionsNotReportedException extends BusinessException {

    public KeyPermissionsNotReportedException() {
        super("API key permissions were not reported; expected \""
                + ReportedKeyPermissions.READ_ONLY + "\"");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.EXCHANGE_KEY_PERMISSIONS_NOT_REPORTED;
    }
}
