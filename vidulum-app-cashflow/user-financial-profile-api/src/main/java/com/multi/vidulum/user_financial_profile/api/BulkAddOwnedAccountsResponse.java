package com.multi.vidulum.user_financial_profile.api;

import java.util.List;

public record BulkAddOwnedAccountsResponse(
        List<OwnedAccountJson> added
) {}
