package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.app.dto.*;
import com.multi.vidulum.recurring_rules.app.dto.DashboardResponse;
import com.multi.vidulum.recurring_rules.app.dto.UpcomingTransactionsResponse;
import com.multi.vidulum.recurring_rules.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP Actor for Recurring Rules API testing.
 * Encapsulates all REST interactions with /api/v1/recurring-rules endpoints.
 */
@Slf4j
public class RecurringRulesHttpActor {

    private final TestRestTemplate restTemplate;
    private final RestTemplate rawRestTemplate;
    private final String baseUrl;
    private String jwtToken;

    public RecurringRulesHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port + "/api/v1/recurring-rules";
        this.rawRestTemplate = new RestTemplate();
    }

    /**
     * Sets the JWT token for authenticated requests.
     */
    public void setJwtToken(String token) {
        this.jwtToken = token;
        log.debug("JWT token set for RecurringRulesHttpActor");
    }

    // ============ Create Rule Operations ============

    /**
     * Creates a daily recurring rule.
     */
    public String createDailyRule(String cashFlowId, String name, String description,
                                   Money baseAmount, String category,
                                   LocalDate startDate, LocalDate endDate,
                                   int intervalDays) {
        PatternDto pattern = PatternDto.builder()
                .type(RecurrenceType.DAILY)
                .intervalDays(intervalDays)
                .build();

        return createRule(cashFlowId, name, description, baseAmount, category, pattern, startDate, endDate);
    }

    /**
     * Creates a weekly recurring rule.
     */
    public String createWeeklyRule(String cashFlowId, String name, String description,
                                    Money baseAmount, String category,
                                    LocalDate startDate, LocalDate endDate,
                                    DayOfWeek dayOfWeek, int intervalWeeks) {
        PatternDto pattern = PatternDto.builder()
                .type(RecurrenceType.WEEKLY)
                .dayOfWeek(dayOfWeek)
                .intervalWeeks(intervalWeeks)
                .build();

        return createRule(cashFlowId, name, description, baseAmount, category, pattern, startDate, endDate);
    }

    /**
     * Creates a monthly recurring rule.
     */
    public String createMonthlyRule(String cashFlowId, String name, String description,
                                     Money baseAmount, String category,
                                     LocalDate startDate, LocalDate endDate,
                                     int dayOfMonth, int intervalMonths, boolean adjustForMonthEnd) {
        PatternDto pattern = PatternDto.builder()
                .type(RecurrenceType.MONTHLY)
                .dayOfMonth(dayOfMonth)
                .intervalMonths(intervalMonths)
                .adjustForMonthEnd(adjustForMonthEnd)
                .build();

        return createRule(cashFlowId, name, description, baseAmount, category, pattern, startDate, endDate);
    }

    /**
     * Creates a monthly recurring rule with advanced options.
     */
    public String createMonthlyRuleWithAdvancedOptions(String cashFlowId, String name, String description,
                                                        Money baseAmount, String category,
                                                        LocalDate startDate, LocalDate endDate,
                                                        int dayOfMonth, int intervalMonths, boolean adjustForMonthEnd,
                                                        Integer maxOccurrences, List<Month> activeMonths, List<LocalDate> excludedDates) {
        PatternDto pattern = PatternDto.builder()
                .type(RecurrenceType.MONTHLY)
                .dayOfMonth(dayOfMonth)
                .intervalMonths(intervalMonths)
                .adjustForMonthEnd(adjustForMonthEnd)
                .build();

        return createRuleWithAdvancedOptions(cashFlowId, name, description, baseAmount, category, pattern,
                startDate, endDate, maxOccurrences, activeMonths, excludedDates);
    }

    /**
     * Creates a weekly recurring rule with advanced options.
     */
    public String createWeeklyRuleWithAdvancedOptions(String cashFlowId, String name, String description,
                                                       Money baseAmount, String category,
                                                       LocalDate startDate, LocalDate endDate,
                                                       DayOfWeek dayOfWeek, int intervalWeeks,
                                                       Integer maxOccurrences, List<Month> activeMonths, List<LocalDate> excludedDates) {
        PatternDto pattern = PatternDto.builder()
                .type(RecurrenceType.WEEKLY)
                .dayOfWeek(dayOfWeek)
                .intervalWeeks(intervalWeeks)
                .build();

        return createRuleWithAdvancedOptions(cashFlowId, name, description, baseAmount, category, pattern,
                startDate, endDate, maxOccurrences, activeMonths, excludedDates);
    }

    /**
     * Creates a monthly recurring rule with last day of month (-1).
     */
    public String createMonthlyRuleLastDayOfMonth(String cashFlowId, String name, String description,
                                                   Money baseAmount, String category,
                                                   LocalDate startDate, LocalDate endDate,
                                                   int intervalMonths) {
        PatternDto pattern = PatternDto.builder()
                .type(RecurrenceType.MONTHLY)
                .dayOfMonth(-1) // Last day of month
                .intervalMonths(intervalMonths)
                .adjustForMonthEnd(false)
                .build();

        return createRule(cashFlowId, name, description, baseAmount, category, pattern, startDate, endDate);
    }

    /**
     * Creates a yearly recurring rule.
     */
    public String createYearlyRule(String cashFlowId, String name, String description,
                                    Money baseAmount, String category,
                                    LocalDate startDate, LocalDate endDate,
                                    int month, int dayOfMonth) {
        PatternDto pattern = PatternDto.builder()
                .type(RecurrenceType.YEARLY)
                .month(month)
                .yearlyDayOfMonth(dayOfMonth)
                .build();

        return createRule(cashFlowId, name, description, baseAmount, category, pattern, startDate, endDate);
    }

    /**
     * Creates a quarterly recurring rule.
     *
     * @param monthInQuarter 1 = first month of quarter (Jan, Apr, Jul, Oct),
     *                       2 = second month (Feb, May, Aug, Nov),
     *                       3 = third month (Mar, Jun, Sep, Dec)
     * @param dayOfMonth day of month (1-31) or -1 for last day of month
     */
    public String createQuarterlyRule(String cashFlowId, String name, String description,
                                       Money baseAmount, String category,
                                       LocalDate startDate, LocalDate endDate,
                                       int monthInQuarter, int dayOfMonth) {
        PatternDto pattern = PatternDto.builder()
                .type(RecurrenceType.QUARTERLY)
                .monthInQuarter(monthInQuarter)
                .dayOfMonth(dayOfMonth)
                .build();

        return createRule(cashFlowId, name, description, baseAmount, category, pattern, startDate, endDate);
    }

    /**
     * Creates a one-time (once) recurring rule that executes exactly once on the target date.
     * The rule will automatically transition to COMPLETED status after generation.
     */
    public String createOnceRule(String cashFlowId, String name, String description,
                                  Money baseAmount, String category,
                                  LocalDate targetDate) {
        PatternDto pattern = PatternDto.builder()
                .type(RecurrenceType.ONCE)
                .targetDate(targetDate)
                .build();

        // For ONCE pattern, startDate and endDate are the same as targetDate
        return createRule(cashFlowId, name, description, baseAmount, category, pattern, targetDate, targetDate);
    }

    /**
     * Creates an every-N-days recurring rule.
     *
     * @param intervalDays number of days between occurrences (1-365)
     * @param preferredDayOfWeek optional preferred day of week (can be null)
     */
    public String createEveryNDaysRule(String cashFlowId, String name, String description,
                                        Money baseAmount, String category,
                                        LocalDate startDate, LocalDate endDate,
                                        int intervalDays, DayOfWeek preferredDayOfWeek) {
        PatternDto pattern = PatternDto.builder()
                .type(RecurrenceType.EVERY_N_DAYS)
                .intervalDays(intervalDays)
                .preferredDayOfWeek(preferredDayOfWeek)
                .build();

        return createRule(cashFlowId, name, description, baseAmount, category, pattern, startDate, endDate);
    }

    private String userId;

    /**
     * Sets the User ID for creating recurring rules.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Creates a recurring rule with a custom pattern.
     */
    public String createRule(String cashFlowId, String name, String description,
                              Money baseAmount, String category, PatternDto pattern,
                              LocalDate startDate, LocalDate endDate) {
        return createRuleWithAdvancedOptions(cashFlowId, name, description, baseAmount, category, pattern,
                startDate, endDate, null, null, null);
    }

    /**
     * Creates a recurring rule with a custom pattern and advanced options.
     */
    public String createRuleWithAdvancedOptions(String cashFlowId, String name, String description,
                                                 Money baseAmount, String category, PatternDto pattern,
                                                 LocalDate startDate, LocalDate endDate,
                                                 Integer maxOccurrences, List<Month> activeMonths, List<LocalDate> excludedDates) {
        if (userId == null) {
            throw new IllegalStateException("userId must be set before creating a rule. Call setUserId() first.");
        }

        CreateRuleRequest request = CreateRuleRequest.builder()
                .userId(userId)
                .cashFlowId(cashFlowId)
                .name(name)
                .description(description)
                .baseAmount(baseAmount)
                .category(category)
                .pattern(pattern)
                .startDate(startDate)
                .endDate(endDate)
                .maxOccurrences(maxOccurrences)
                .activeMonths(activeMonths)
                .excludedDates(excludedDates)
                .build();

        ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                baseUrl,
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, String>>() {}
        );

        log.info("Create rule response: status={}, body={}", response.getStatusCode(), response.getBody());
        assertThat(response.getStatusCode())
                .as("Expected 201 CREATED but got %s with body: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        String ruleId = response.getBody().get("ruleId");
        assertThat(ruleId).isNotNull().startsWith("RR");

        log.info("Created recurring rule via HTTP: id={}, name={}, pattern={}", ruleId, name, pattern.getType());
        return ruleId;
    }

    // ============ Get Rule Operations ============

    /**
     * Gets a recurring rule by ID.
     */
    public RecurringRuleResponse getRule(String ruleId) {
        ResponseEntity<RecurringRuleResponse> response = restTemplate.exchange(
                baseUrl + "/" + ruleId,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                RecurringRuleResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Gets all rules for a CashFlow.
     */
    public List<RecurringRuleResponse> getRulesByCashFlow(String cashFlowId) {
        ResponseEntity<List<RecurringRuleResponse>> response = restTemplate.exchange(
                baseUrl + "/cash-flow/" + cashFlowId,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                new ParameterizedTypeReference<List<RecurringRuleResponse>>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Gets all rules for the current user.
     */
    public List<RecurringRuleResponse> getMyRules() {
        ResponseEntity<List<RecurringRuleResponse>> response = restTemplate.exchange(
                baseUrl + "/me",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                new ParameterizedTypeReference<List<RecurringRuleResponse>>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Gets dashboard for the current user filtered by cashFlowId.
     * Returns summary of rules (active/paused/completed counts),
     * monthly projection (expenses/income/net balance),
     * and upcoming transactions.
     *
     * @param cashFlowId the cashflow to filter by (required)
     */
    public DashboardResponse getMyDashboard(String cashFlowId) {
        return getMyDashboard(cashFlowId, 7, 1);
    }

    /**
     * Gets dashboard for the current user with custom parameters.
     *
     * @param cashFlowId the cashflow to filter by (required)
     * @param upcomingDays number of days to look ahead for upcoming transactions (default 7)
     * @param projectionMonths number of months for projection (default 1)
     */
    public DashboardResponse getMyDashboard(String cashFlowId, int upcomingDays, int projectionMonths) {
        String url = baseUrl + "/me/dashboard?cashFlowId=" + cashFlowId +
                "&upcomingDays=" + upcomingDays +
                "&projectionMonths=" + projectionMonths;

        ResponseEntity<DashboardResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                DashboardResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("Got dashboard for current user: cashFlowId={}, activeRules={}, pausedRules={}, completedRules={}",
                cashFlowId,
                response.getBody().getSummary().getActiveRulesCount(),
                response.getBody().getSummary().getPausedRulesCount(),
                response.getBody().getSummary().getCompletedRulesCount());
        return response.getBody();
    }

    /**
     * Gets upcoming transactions for the current user with default parameters (30 days, 20 limit).
     *
     * @param cashFlowId the cashflow to filter by (required)
     */
    public UpcomingTransactionsResponse getMyUpcoming(String cashFlowId) {
        return getMyUpcoming(cashFlowId, 30, 20);
    }

    /**
     * Gets upcoming transactions for the current user with custom parameters.
     *
     * @param cashFlowId the cashflow to filter by (required)
     * @param days number of days to look ahead (default 30)
     * @param limit maximum number of transactions to return (default 20)
     */
    public UpcomingTransactionsResponse getMyUpcoming(String cashFlowId, int days, int limit) {
        String url = baseUrl + "/me/upcoming?cashFlowId=" + cashFlowId +
                "&days=" + days + "&limit=" + limit;

        ResponseEntity<UpcomingTransactionsResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                UpcomingTransactionsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("Got upcoming transactions: cashFlowId={}, count={}, days={}, limit={}",
                cashFlowId, response.getBody().getTotalCount(), days, limit);
        return response.getBody();
    }

    /**
     * Gets all rules for a specific user by userId.
     */
    public List<RecurringRuleResponse> getRulesByUser(String userId) {
        ResponseEntity<List<RecurringRuleResponse>> response = restTemplate.exchange(
                baseUrl + "/user/" + userId,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                new ParameterizedTypeReference<List<RecurringRuleResponse>>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    // ============ Update Rule Operations ============

    /**
     * Updates a recurring rule.
     */
    public void updateRule(String ruleId, String name, String description,
                           Money baseAmount, String category, PatternDto pattern,
                           LocalDate startDate, LocalDate endDate) {
        updateRuleWithAdvancedOptions(ruleId, name, description, baseAmount, category, pattern,
                startDate, endDate, null, null, null);
    }

    /**
     * Updates a recurring rule with advanced options.
     */
    public void updateRuleWithAdvancedOptions(String ruleId, String name, String description,
                                               Money baseAmount, String category, PatternDto pattern,
                                               LocalDate startDate, LocalDate endDate,
                                               Integer maxOccurrences, List<Month> activeMonths, List<LocalDate> excludedDates) {
        UpdateRuleRequest request = UpdateRuleRequest.builder()
                .name(name)
                .description(description)
                .baseAmount(baseAmount)
                .category(category)
                .pattern(pattern)
                .startDate(startDate)
                .endDate(endDate)
                .maxOccurrences(maxOccurrences)
                .activeMonths(activeMonths)
                .excludedDates(excludedDates)
                .build();

        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/" + ruleId,
                HttpMethod.PUT,
                new HttpEntity<>(request, jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("Updated recurring rule via HTTP: id={}", ruleId);
    }

    // ============ Pause/Resume Operations ============

    /**
     * Pauses a recurring rule.
     */
    public void pauseRule(String ruleId, LocalDate resumeDate, String reason) {
        PauseRuleRequest request = PauseRuleRequest.builder()
                .resumeDate(resumeDate)
                .reason(reason)
                .build();

        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/" + ruleId + "/pause",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("Paused recurring rule via HTTP: id={}, until={}", ruleId, resumeDate);
    }

    /**
     * Resumes a paused recurring rule.
     */
    public void resumeRule(String ruleId) {
        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/" + ruleId + "/resume",
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("Resumed recurring rule via HTTP: id={}", ruleId);
    }

    // ============ Delete Operation ============

    /**
     * Deletes a recurring rule.
     */
    public void deleteRule(String ruleId, String reason) {
        DeleteRuleRequest request = DeleteRuleRequest.builder()
                .reason(reason)
                .build();

        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/" + ruleId,
                HttpMethod.DELETE,
                new HttpEntity<>(request, jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        log.info("Deleted recurring rule via HTTP: id={}", ruleId);
    }

    // ============ Regenerate Operation ============

    /**
     * Regenerates expected cash changes for a rule.
     */
    public void regenerateExpectedCashChanges(String ruleId) {
        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/" + ruleId + "/regenerate",
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("Regenerated expected cash changes for rule via HTTP: id={}", ruleId);
    }

    // ============ Error-Expecting Operations ============

    /**
     * Creates a rule expecting an error response.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> createRuleExpectingError(CreateRuleRequest request) {
        try {
            return rawRestTemplate.exchange(
                    baseUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(request, jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, String>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, String> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    /**
     * Gets a rule expecting an error response.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> getRuleExpectingError(String ruleId) {
        try {
            return rawRestTemplate.exchange(
                    baseUrl + "/" + ruleId,
                    HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, String>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, String> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    /**
     * Pauses a rule expecting an error response.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> pauseRuleExpectingError(String ruleId, LocalDate resumeDate, String reason) {
        PauseRuleRequest request = PauseRuleRequest.builder()
                .resumeDate(resumeDate)
                .reason(reason)
                .build();

        try {
            return rawRestTemplate.exchange(
                    baseUrl + "/" + ruleId + "/pause",
                    HttpMethod.POST,
                    new HttpEntity<>(request, jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, String>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, String> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    /**
     * Resumes a rule expecting an error response.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> resumeRuleExpectingError(String ruleId) {
        try {
            return rawRestTemplate.exchange(
                    baseUrl + "/" + ruleId + "/resume",
                    HttpMethod.POST,
                    new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, String>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, String> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    // ============ Amount Changes Operations ============

    /**
     * Adds an amount change to a recurring rule.
     *
     * @param ruleId the rule ID
     * @param amount the amount to add (positive or negative)
     * @param type ONE_TIME or PERMANENT
     * @param reason optional reason for the change
     * @return the created amount change ID
     */
    public String addAmountChange(String ruleId, Money amount, AmountChangeType type, String reason) {
        AddAmountChangeRequest request = AddAmountChangeRequest.builder()
                .amount(amount)
                .type(type)
                .reason(reason)
                .build();

        ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                baseUrl + "/" + ruleId + "/amount-changes",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, String>>() {}
        );

        assertThat(response.getStatusCode())
                .as("Expected 201 CREATED but got %s with body: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String changeId = response.getBody().get("amountChangeId");
        assertThat(changeId).isNotNull().startsWith("AC");

        log.info("Added amount change via HTTP: ruleId={}, changeId={}, type={}, amount={}",
                ruleId, changeId, type, amount);
        return changeId;
    }

    /**
     * Gets all amount changes for a recurring rule.
     */
    public List<AmountChangeResponse> getAmountChanges(String ruleId) {
        ResponseEntity<List<AmountChangeResponse>> response = restTemplate.exchange(
                baseUrl + "/" + ruleId + "/amount-changes",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                new ParameterizedTypeReference<List<AmountChangeResponse>>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Removes an amount change from a recurring rule.
     */
    public void removeAmountChange(String ruleId, String amountChangeId) {
        ResponseEntity<Void> response = restTemplate.exchange(
                baseUrl + "/" + ruleId + "/amount-changes/" + amountChangeId,
                HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders()),
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        log.info("Removed amount change via HTTP: ruleId={}, changeId={}", ruleId, amountChangeId);
    }

    /**
     * Adds an amount change expecting an error response.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> addAmountChangeExpectingError(String ruleId, AddAmountChangeRequest request) {
        try {
            return rawRestTemplate.exchange(
                    baseUrl + "/" + ruleId + "/amount-changes",
                    HttpMethod.POST,
                    new HttpEntity<>(request, jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, String>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, String> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    /**
     * Removes an amount change expecting an error response.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> removeAmountChangeExpectingError(String ruleId, String amountChangeId) {
        try {
            rawRestTemplate.exchange(
                    baseUrl + "/" + ruleId + "/amount-changes/" + amountChangeId,
                    HttpMethod.DELETE,
                    new HttpEntity<>(jsonHeaders()),
                    Void.class
            );
            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (HttpClientErrorException e) {
            Map<String, Object> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    // ============ Impact Preview Operation ============

    /**
     * Gets delete impact preview for a recurring rule.
     * Shows what will happen when the rule is deleted:
     * - Future occurrences that will be removed
     * - Generated transactions (pending vs confirmed)
     * - Forecast impact (affected months)
     * - Warnings and recommendations
     */
    public DeleteImpactPreviewResponse getDeleteImpactPreview(String ruleId) {
        ResponseEntity<DeleteImpactPreviewResponse> response = restTemplate.exchange(
                baseUrl + "/" + ruleId + "/impact-preview",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                DeleteImpactPreviewResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("Got delete impact preview for rule: id={}", ruleId);
        return response.getBody();
    }

    /**
     * Gets delete impact preview expecting an error response.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> getDeleteImpactPreviewExpectingError(String ruleId) {
        try {
            return rawRestTemplate.exchange(
                    baseUrl + "/" + ruleId + "/impact-preview",
                    HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, String>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, String> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    /**
     * Deletes a rule expecting an error response.
     * Used for testing error handling (400 validation, 404 not found).
     */
    public ResponseEntity<Map<String, String>> deleteRuleExpectingError(String ruleId) {
        try {
            return rawRestTemplate.exchange(
                    baseUrl + "/" + ruleId,
                    HttpMethod.DELETE,
                    new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, String>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, String> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    // ============ Dashboard/Upcoming Error-Expecting Operations ============

    /**
     * Gets dashboard expecting an error response.
     * Used for testing validation errors (missing cashFlowId, invalid parameters).
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> getMyDashboardExpectingError(String cashFlowId, int upcomingDays, int projectionMonths) {
        String url = baseUrl + "/me/dashboard?cashFlowId=" + cashFlowId +
                "&upcomingDays=" + upcomingDays +
                "&projectionMonths=" + projectionMonths;
        try {
            return rawRestTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, Object> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    /**
     * Gets dashboard without cashFlowId expecting an error response.
     * Used for testing missing required parameter.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> getMyDashboardWithoutCashFlowIdExpectingError() {
        String url = baseUrl + "/me/dashboard";
        try {
            return rawRestTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, Object> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    /**
     * Gets upcoming transactions expecting an error response.
     * Used for testing validation errors.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> getMyUpcomingExpectingError(String cashFlowId, int days, int limit) {
        String url = baseUrl + "/me/upcoming?cashFlowId=" + cashFlowId +
                "&days=" + days + "&limit=" + limit;
        try {
            return rawRestTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, Object> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    /**
     * Gets upcoming transactions without cashFlowId expecting an error response.
     * Used for testing missing required parameter.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> getMyUpcomingWithoutCashFlowIdExpectingError() {
        String url = baseUrl + "/me/upcoming";
        try {
            return rawRestTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
        } catch (HttpClientErrorException e) {
            Map<String, Object> errorBody = e.getResponseBodyAs(Map.class);
            return ResponseEntity.status(e.getStatusCode()).body(errorBody);
        }
    }

    // ============ Helper Methods ============

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwtToken != null) {
            headers.setBearerAuth(jwtToken);
        }
        return headers;
    }
}
