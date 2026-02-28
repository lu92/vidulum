package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.app.dto.*;
import com.multi.vidulum.recurring_rules.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
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
        UpdateRuleRequest request = UpdateRuleRequest.builder()
                .name(name)
                .description(description)
                .baseAmount(baseAmount)
                .category(category)
                .pattern(pattern)
                .startDate(startDate)
                .endDate(endDate)
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
