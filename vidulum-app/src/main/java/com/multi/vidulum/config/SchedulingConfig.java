package com.multi.vidulum.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration to enable Spring scheduling for background jobs.
 * <p>
 * This enables @Scheduled annotations in the application, used by:
 * <ul>
 *   <li>{@code MonthlyRolloverScheduler} - runs at 02:00 UTC on 1st of each month</li>
 *   <li>{@code RecurringRuleAutoResumeScheduler} - runs at 03:00 UTC daily</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
