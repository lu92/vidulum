package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.common.UserId;

/**
 * Client for querying the user's owned bank accounts.
 * Used by bank data ingestion for self-transfer detection.
 *
 * <p>Production implementation calls the user-financial-profile REST API.
 * Test implementation returns in-memory data.
 */
public interface OwnedAccountClient {
    OwnedAccountRegistry loadForUser(UserId userId);
}
