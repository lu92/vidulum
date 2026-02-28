package com.multi.vidulum.recurring_rules.domain;

public enum ExecutionStatus {
    SUCCESS,     // Transaction generated successfully
    FAILED,      // Error during generation
    SKIPPED      // Skipped (e.g., rule was paused)
}
