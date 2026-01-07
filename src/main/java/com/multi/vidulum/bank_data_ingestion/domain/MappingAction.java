package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Defines what action to take when mapping a bank category to a system category.
 */
public enum MappingAction {
    /**
     * Create a new top-level category with the target name.
     */
    CREATE_NEW,

    /**
     * Create a new subcategory under an existing parent category.
     */
    CREATE_SUBCATEGORY,

    /**
     * Map to an existing category in the system.
     */
    MAP_TO_EXISTING,

    /**
     * Map to the special "Uncategorized" category.
     */
    MAP_TO_UNCATEGORIZED
}
