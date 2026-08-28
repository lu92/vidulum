package com.multi.vidulum.user_financial_profile.api;

import java.util.List;

public record OwnedAccountsListJson(
        String userId,
        List<OwnedAccountJson> accounts
) {}
