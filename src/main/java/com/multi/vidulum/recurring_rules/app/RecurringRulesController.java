package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.recurring_rules.app.commands.*;
import com.multi.vidulum.recurring_rules.app.dto.*;
import com.multi.vidulum.recurring_rules.app.queries.*;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.recurring_rules.domain.exceptions.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/recurring-rules")
@RequiredArgsConstructor
public class RecurringRulesController {

    private final RecurringRuleService ruleService;

    @PostMapping
    public ResponseEntity<Map<String, String>> createRule(
            @Valid @RequestBody CreateRuleRequest request,
            @RequestHeader("Authorization") String authHeader
    ) throws RecurringRuleException {
        String authToken = extractToken(authHeader);

        CreateRuleCommand command = new CreateRuleCommand(
                request.getUserId(),
                request.getCashFlowId(),
                request.getName(),
                request.getDescription(),
                request.getBaseAmount(),
                new CategoryName(request.getCategory()),
                request.getPattern().toPattern(),
                request.getStartDate(),
                request.getEndDate()
        );

        RecurringRuleId ruleId = ruleService.handle(command, authToken);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("ruleId", ruleId.id()));
    }

    @GetMapping("/{ruleId}")
    public ResponseEntity<RecurringRuleResponse> getRule(@PathVariable String ruleId) throws RuleNotFoundException {
        GetRuleQuery query = new GetRuleQuery(ruleId);
        RecurringRuleSnapshot snapshot = ruleService.handle(query);
        return ResponseEntity.ok(RecurringRuleResponse.fromSnapshot(snapshot));
    }

    @GetMapping("/cash-flow/{cashFlowId}")
    public ResponseEntity<List<RecurringRuleResponse>> getRulesByCashFlow(@PathVariable String cashFlowId) {
        GetRulesByCashFlowQuery query = new GetRulesByCashFlowQuery(cashFlowId);
        List<RecurringRuleSnapshot> snapshots = ruleService.handle(query);
        List<RecurringRuleResponse> responses = snapshots.stream()
                .map(RecurringRuleResponse::fromSnapshot)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<RecurringRuleResponse>> getRulesByUser(@PathVariable String userId) {
        GetRulesByUserQuery query = new GetRulesByUserQuery(userId);
        List<RecurringRuleSnapshot> snapshots = ruleService.handle(query);
        List<RecurringRuleResponse> responses = snapshots.stream()
                .map(RecurringRuleResponse::fromSnapshot)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/me")
    public ResponseEntity<List<RecurringRuleResponse>> getMyRules() {
        String userId = getCurrentUserId();
        GetRulesByUserQuery query = new GetRulesByUserQuery(userId);
        List<RecurringRuleSnapshot> snapshots = ruleService.handle(query);
        List<RecurringRuleResponse> responses = snapshots.stream()
                .map(RecurringRuleResponse::fromSnapshot)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<Void> updateRule(
            @PathVariable String ruleId,
            @Valid @RequestBody UpdateRuleRequest request,
            @RequestHeader("Authorization") String authHeader
    ) throws RecurringRuleException {
        String authToken = extractToken(authHeader);

        UpdateRuleCommand command = new UpdateRuleCommand(
                ruleId,
                request.getName(),
                request.getDescription(),
                request.getBaseAmount(),
                new CategoryName(request.getCategory()),
                request.getPattern().toPattern(),
                request.getStartDate(),
                request.getEndDate()
        );

        ruleService.handle(command, authToken);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{ruleId}/pause")
    public ResponseEntity<Void> pauseRule(
            @PathVariable String ruleId,
            @RequestBody PauseRuleRequest request,
            @RequestHeader("Authorization") String authHeader
    ) throws RecurringRuleException {
        String authToken = extractToken(authHeader);

        PauseRuleCommand command = new PauseRuleCommand(
                ruleId,
                request.getResumeDate(),
                request.getReason()
        );

        ruleService.handle(command, authToken);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{ruleId}/resume")
    public ResponseEntity<Void> resumeRule(
            @PathVariable String ruleId,
            @RequestHeader("Authorization") String authHeader
    ) throws RecurringRuleException {
        String authToken = extractToken(authHeader);

        ResumeRuleCommand command = new ResumeRuleCommand(ruleId);
        ruleService.handle(command, authToken);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            @PathVariable String ruleId,
            @RequestBody(required = false) DeleteRuleRequest request,
            @RequestHeader("Authorization") String authHeader
    ) throws RecurringRuleException {
        String authToken = extractToken(authHeader);
        String reason = request != null ? request.getReason() : "User requested deletion";

        DeleteRuleCommand command = new DeleteRuleCommand(ruleId, reason);
        ruleService.handle(command, authToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{ruleId}/regenerate")
    public ResponseEntity<Void> regenerateExpectedCashChanges(
            @PathVariable String ruleId,
            @RequestHeader("Authorization") String authHeader
    ) throws RecurringRuleException {
        String authToken = extractToken(authHeader);

        GenerateExpectedCashChangesCommand command = new GenerateExpectedCashChangesCommand(ruleId);
        ruleService.handle(command, authToken);
        return ResponseEntity.ok().build();
    }

    private String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        throw new IllegalStateException("No authenticated user found");
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }

    // Exception handlers

    @ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleRuleNotFound(RuleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "error", "RULE_NOT_FOUND",
                        "message", ex.getMessage(),
                        "ruleId", ex.getRuleId().id()
                ));
    }

    @ExceptionHandler(CashFlowNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleCashFlowNotFound(CashFlowNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "error", "CASHFLOW_NOT_FOUND",
                        "message", ex.getMessage(),
                        "cashFlowId", ex.getCashFlowId().id()
                ));
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleCategoryNotFound(CategoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "CATEGORY_NOT_FOUND",
                        "message", ex.getMessage(),
                        "cashFlowId", ex.getCashFlowId().id(),
                        "category", ex.getCategoryName().name()
                ));
    }

    @ExceptionHandler(InvalidRuleStateException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRuleState(InvalidRuleStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "INVALID_RULE_STATE",
                        "message", ex.getMessage(),
                        "ruleId", ex.getRuleId().id(),
                        "currentStatus", ex.getCurrentStatus().name(),
                        "operation", ex.getOperation()
                ));
    }

    @ExceptionHandler(InvalidDateRangeException.class)
    public ResponseEntity<Map<String, String>> handleInvalidDateRange(InvalidDateRangeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "INVALID_DATE_RANGE",
                        "message", ex.getMessage(),
                        "startDate", ex.getStartDate().toString(),
                        "endDate", ex.getEndDate().toString()
                ));
    }

    @ExceptionHandler(CashFlowCommunicationException.class)
    public ResponseEntity<Map<String, String>> handleCashFlowCommunication(CashFlowCommunicationException ex) {
        log.error("CashFlow communication error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "CASHFLOW_COMMUNICATION_ERROR",
                        "message", ex.getMessage(),
                        "cashFlowId", ex.getCashFlowId().id(),
                        "operation", ex.getOperation()
                ));
    }

    @ExceptionHandler(RecurringRuleException.class)
    public ResponseEntity<Map<String, String>> handleGenericRuleException(RecurringRuleException ex) {
        log.error("Recurring rule error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", "RECURRING_RULE_ERROR",
                        "message", ex.getMessage()
                ));
    }
}
