package com.multi.vidulum.recurring_rules.app;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.recurring_rules.app.commands.addamountchange.AddAmountChangeCommand;
import com.multi.vidulum.recurring_rules.app.commands.generate.GenerateExpectedCashChangesCommand;
import com.multi.vidulum.recurring_rules.app.commands.removeamountchange.RemoveAmountChangeCommand;
import com.multi.vidulum.recurring_rules.app.commands.create.CreateRuleCommand;
import com.multi.vidulum.recurring_rules.app.commands.delete.DeleteRuleCommand;
import com.multi.vidulum.recurring_rules.app.commands.pause.PauseRuleCommand;
import com.multi.vidulum.recurring_rules.app.commands.resume.ResumeRuleCommand;
import com.multi.vidulum.recurring_rules.app.commands.update.UpdateRuleCommand;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.recurring_rules.app.dto.*;
import com.multi.vidulum.recurring_rules.app.queries.*;
import com.multi.vidulum.recurring_rules.domain.AmountChangeId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDashboardParameterException;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/recurring-rules")
@RequiredArgsConstructor
public class RecurringRulesController {

    private final RecurringRuleService ruleService;
    // Two commands go through the gateway, the rest still call the service directly. The mixture is
    // the honest state of the migration, not a design: a command gets a handler of its own when
    // someone has a reason to touch it.
    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    @PostMapping
    public ResponseEntity<Map<String, String>> createRule(
            @Valid @RequestBody CreateRuleRequest request
    ) {
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

        RecurringRuleId ruleId = commandGateway.send(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("ruleId", ruleId.id()));
    }

    @GetMapping("/{ruleId}")
    public ResponseEntity<RecurringRuleResponse> getRule(@PathVariable String ruleId) {
        RecurringRuleSnapshot snapshot = queryGateway.send(new GetRuleQuery(ruleId));
        return ResponseEntity.ok(RecurringRuleResponse.fromSnapshot(snapshot));
    }

    @GetMapping("/cash-flow/{cashFlowId}")
    public ResponseEntity<List<RecurringRuleResponse>> getRulesByCashFlow(@PathVariable String cashFlowId) {
        List<RecurringRuleSnapshot> snapshots = queryGateway.send(new GetRulesByCashFlowQuery(cashFlowId));
        List<RecurringRuleResponse> responses = snapshots.stream()
                .map(RecurringRuleResponse::fromSnapshot)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<RecurringRuleResponse>> getRulesByUser(@PathVariable String userId) {
        List<RecurringRuleSnapshot> snapshots = queryGateway.send(new GetRulesByUserQuery(userId));
        List<RecurringRuleResponse> responses = snapshots.stream()
                .map(RecurringRuleResponse::fromSnapshot)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/me")
    public ResponseEntity<List<RecurringRuleResponse>> getMyRules() {
        String userId = getCurrentUserId();
        List<RecurringRuleSnapshot> snapshots = queryGateway.send(new GetRulesByUserQuery(userId));
        List<RecurringRuleResponse> responses = snapshots.stream()
                .map(RecurringRuleResponse::fromSnapshot)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/me/dashboard")
    public ResponseEntity<DashboardResponse> getMyDashboard(
            @RequestParam String cashFlowId,
            @RequestParam(defaultValue = "7") int upcomingDays,
            @RequestParam(defaultValue = "1") int projectionMonths
    ) {
        // Validate parameters
        if (upcomingDays < 1 || upcomingDays > 90) {
            throw new InvalidDashboardParameterException("upcomingDays", upcomingDays, 1, 90);
        }
        if (projectionMonths < 1 || projectionMonths > 12) {
            throw new InvalidDashboardParameterException("projectionMonths", projectionMonths, 1, 12);
        }

        String userId = getCurrentUserId();
        DashboardResponse dashboard = ruleService.getDashboard(userId, cashFlowId, upcomingDays, projectionMonths);
        return ResponseEntity.ok(dashboard);
    }

    @GetMapping("/me/upcoming")
    public ResponseEntity<UpcomingTransactionsResponse> getMyUpcomingTransactions(
            @RequestParam String cashFlowId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit
    ) {
        // Validate parameters
        if (days < 1 || days > 90) {
            throw new InvalidDashboardParameterException("days", days, 1, 90);
        }
        if (limit < 1 || limit > 100) {
            throw new InvalidDashboardParameterException("limit", limit, 1, 100);
        }

        String userId = getCurrentUserId();
        UpcomingTransactionsResponse upcoming = ruleService.getUpcomingTransactions(userId, cashFlowId, days, limit);
        return ResponseEntity.ok(upcoming);
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<Void> updateRule(
            @PathVariable String ruleId,
            @Valid @RequestBody UpdateRuleRequest request
    ) {
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

        commandGateway.send(command);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{ruleId}/pause")
    public ResponseEntity<Void> pauseRule(
            @PathVariable String ruleId,
            @RequestBody PauseRuleRequest request
    ) {
        PauseRuleCommand command = new PauseRuleCommand(
                ruleId,
                request.getResumeDate(),
                request.getReason()
        );

        commandGateway.send(command);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{ruleId}/resume")
    public ResponseEntity<Void> resumeRule(
            @PathVariable String ruleId
    ) {
        ResumeRuleCommand command = new ResumeRuleCommand(ruleId);
        commandGateway.send(command);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            @PathVariable String ruleId,
            @RequestBody(required = false) DeleteRuleRequest request
    ) {
        String reason = request != null ? request.getReason() : "User requested deletion";

        DeleteRuleCommand command = new DeleteRuleCommand(ruleId, reason);
        commandGateway.send(command);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{ruleId}/impact-preview")
    public ResponseEntity<DeleteImpactPreviewResponse> getDeleteImpactPreview(
            @PathVariable String ruleId,
            @RequestHeader("Authorization") String authHeader
    ) {
        String authToken = extractToken(authHeader);

        PreviewDeleteImpactQuery query = new PreviewDeleteImpactQuery(ruleId, authToken);
        DeleteImpactPreviewResponse response = ruleService.handle(query);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{ruleId}/regenerate")
    public ResponseEntity<Void> regenerateExpectedCashChanges(
            @PathVariable String ruleId
    ) {
        GenerateExpectedCashChangesCommand command = new GenerateExpectedCashChangesCommand(ruleId);
        commandGateway.send(command);
        return ResponseEntity.ok().build();
    }

    // Amount Changes endpoints

    @PostMapping("/{ruleId}/amount-changes")
    public ResponseEntity<Map<String, String>> addAmountChange(
            @PathVariable String ruleId,
            @Valid @RequestBody AddAmountChangeRequest request
    ) {
        AddAmountChangeCommand command = new AddAmountChangeCommand(
                ruleId,
                request.getAmount(),
                request.getType(),
                request.getReason()
        );

        AmountChangeId changeId = commandGateway.send(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("amountChangeId", changeId.id()));
    }

    @GetMapping("/{ruleId}/amount-changes")
    public ResponseEntity<List<AmountChangeResponse>> getAmountChanges(
            @PathVariable String ruleId
    ) {
        RecurringRuleSnapshot snapshot = queryGateway.send(new GetRuleQuery(ruleId));

        List<AmountChangeResponse> changes = snapshot.amountChanges() != null
                ? snapshot.amountChanges().stream().map(AmountChangeResponse::from).toList()
                : List.of();

        return ResponseEntity.ok(changes);
    }

    @DeleteMapping("/{ruleId}/amount-changes/{amountChangeId}")
    public ResponseEntity<Void> removeAmountChange(
            @PathVariable String ruleId,
            @PathVariable String amountChangeId
    ) {
        RemoveAmountChangeCommand command = new RemoveAmountChangeCommand(ruleId, amountChangeId);
        commandGateway.send(command);

        return ResponseEntity.noContent().build();
    }

    private String getCurrentUserId() {
        return authenticatedUserProvider.getCurrentUserId().getId();
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }
}
