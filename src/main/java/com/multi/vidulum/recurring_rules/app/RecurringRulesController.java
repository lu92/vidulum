package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.recurring_rules.app.commands.*;
import com.multi.vidulum.recurring_rules.app.dto.*;
import com.multi.vidulum.recurring_rules.app.queries.*;
import com.multi.vidulum.recurring_rules.domain.AmountChangeId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.recurring_rules.domain.exceptions.RecurringRuleException;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import com.multi.vidulum.user.domain.DomainUserRepository;
import com.multi.vidulum.user.domain.User;
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
    private final DomainUserRepository userRepository;

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
                request.getEndDate(),
                request.getMaxOccurrences(),
                request.getActiveMonths(),
                request.getExcludedDates()
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
                request.getEndDate(),
                request.getMaxOccurrences(),
                request.getActiveMonths(),
                request.getExcludedDates()
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

    // Amount Changes endpoints

    @PostMapping("/{ruleId}/amount-changes")
    public ResponseEntity<Map<String, String>> addAmountChange(
            @PathVariable String ruleId,
            @Valid @RequestBody AddAmountChangeRequest request,
            @RequestHeader("Authorization") String authHeader
    ) throws RecurringRuleException {
        String authToken = extractToken(authHeader);

        AddAmountChangeCommand command = new AddAmountChangeCommand(
                ruleId,
                request.getAmount(),
                request.getType(),
                request.getReason()
        );

        AmountChangeId changeId = ruleService.handle(command, authToken);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("amountChangeId", changeId.id()));
    }

    @GetMapping("/{ruleId}/amount-changes")
    public ResponseEntity<List<AmountChangeResponse>> getAmountChanges(
            @PathVariable String ruleId
    ) throws RuleNotFoundException {
        GetRuleQuery query = new GetRuleQuery(ruleId);
        RecurringRuleSnapshot snapshot = ruleService.handle(query);

        List<AmountChangeResponse> changes = snapshot.amountChanges() != null
                ? snapshot.amountChanges().stream().map(AmountChangeResponse::from).toList()
                : List.of();

        return ResponseEntity.ok(changes);
    }

    @DeleteMapping("/{ruleId}/amount-changes/{amountChangeId}")
    public ResponseEntity<Void> removeAmountChange(
            @PathVariable String ruleId,
            @PathVariable String amountChangeId,
            @RequestHeader("Authorization") String authHeader
    ) throws RecurringRuleException {
        String authToken = extractToken(authHeader);

        RemoveAmountChangeCommand command = new RemoveAmountChangeCommand(ruleId, amountChangeId);
        ruleService.handle(command, authToken);

        return ResponseEntity.noContent().build();
    }

    private String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            String username = authentication.getName();
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new IllegalStateException("User not found: " + username));
            return user.getUserId().getId();
        }
        throw new IllegalStateException("No authenticated user found");
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }
}
