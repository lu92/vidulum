package com.multi.vidulum.user_financial_profile.api;

import java.time.ZonedDateTime;

public record OwnedAccountJson(
        String iban,
        String currency,
        String bankName,
        String label,
        String status,
        String source,
        String linkedCashFlowId,
        ZonedDateTime addedAt,
        ZonedDateTime closedAt
) {}
