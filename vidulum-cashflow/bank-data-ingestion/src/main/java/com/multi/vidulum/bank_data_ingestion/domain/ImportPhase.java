package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Phases of the import process.
 */
public enum ImportPhase {
    /**
     * Creating new categories that don't exist yet.
     */
    CREATING_CATEGORIES,

    /**
     * Importing transactions into the CashFlow.
     */
    IMPORTING_TRANSACTIONS
}
