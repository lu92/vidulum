package com.multi.vidulum.bank_data_adapter.rest;

import com.multi.vidulum.bank_data_adapter.app.MappingRulesCacheService;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for managing AI mapping rules cache.
 * Useful for testing and debugging bank CSV transformations.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/bank-data-adapter/mapping-rules")
@RequiredArgsConstructor
public class MappingRulesController {

    private final MappingRulesCacheService cacheService;

    /**
     * List all cached mapping rules.
     */
    @GetMapping
    public ResponseEntity<List<MappingRulesSummary>> listAll() {
        List<MappingRules> rules = cacheService.listAll();
        List<MappingRulesSummary> summaries = rules.stream()
            .map(this::toSummary)
            .toList();
        return ResponseEntity.ok(summaries);
    }

    /**
     * Get detailed mapping rules by bank identifier.
     */
    @GetMapping("/{bankIdentifier}")
    public ResponseEntity<MappingRules> getByIdentifier(
            @PathVariable String bankIdentifier) {
        return cacheService.findByBankIdentifier(bankIdentifier)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete mapping rules by bank identifier.
     * After deletion, the next CSV upload for this bank will trigger a fresh AI analysis.
     */
    @DeleteMapping("/{bankIdentifier}")
    public ResponseEntity<DeleteResponse> deleteByIdentifier(
            @PathVariable String bankIdentifier) {
        if (!cacheService.exists(bankIdentifier)) {
            return ResponseEntity.notFound().build();
        }
        cacheService.delete(bankIdentifier);
        log.info("Deleted mapping rules: {}", bankIdentifier);
        return ResponseEntity.ok(new DeleteResponse(bankIdentifier, "Mapping rules deleted successfully"));
    }

    /**
     * Delete mapping rules by bank name.
     * Convenience endpoint when you know the bank name but not the identifier.
     */
    @DeleteMapping("/by-bank/{bankName}")
    public ResponseEntity<DeleteResponse> deleteByBankName(
            @PathVariable String bankName) {
        List<MappingRules> rules = cacheService.listAll().stream()
            .filter(r -> bankName.equalsIgnoreCase(r.getBankName()))
            .toList();

        if (rules.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        for (MappingRules rule : rules) {
            cacheService.delete(rule.getBankIdentifier());
            log.info("Deleted mapping rules for bank: {} ({})", rule.getBankName(), rule.getBankIdentifier());
        }

        return ResponseEntity.ok(new DeleteResponse(
            bankName,
            String.format("Deleted %d mapping rule(s) for bank: %s", rules.size(), bankName)
        ));
    }

    /**
     * Delete all cached mapping rules.
     * Use with caution - forces AI re-analysis for all banks.
     */
    @DeleteMapping("/all")
    public ResponseEntity<DeleteResponse> deleteAll() {
        List<MappingRules> rules = cacheService.listAll();
        int count = rules.size();

        for (MappingRules rule : rules) {
            cacheService.delete(rule.getBankIdentifier());
        }

        log.info("Deleted all {} mapping rules", count);
        return ResponseEntity.ok(new DeleteResponse(
            "all",
            String.format("Deleted %d mapping rule(s)", count)
        ));
    }

    // ========== DTOs ==========

    public record MappingRulesSummary(
        String bankIdentifier,
        String bankName,
        String bankCountry,
        String language,
        int columnMappingCount,
        int usageCount,
        String lastUsedAt,
        String createdAt,
        double confidenceScore
    ) {}

    public record DeleteResponse(
        String identifier,
        String message
    ) {}

    private MappingRulesSummary toSummary(MappingRules rules) {
        return new MappingRulesSummary(
            rules.getBankIdentifier(),
            rules.getBankName(),
            rules.getBankCountry(),
            rules.getLanguage(),
            rules.getColumnMappings() != null ? rules.getColumnMappings().size() : 0,
            rules.getUsageCount(),
            rules.getLastUsedAt() != null ? rules.getLastUsedAt().toString() : null,
            rules.getCreatedAt() != null ? rules.getCreatedAt().toString() : null,
            rules.getConfidenceScore()
        );
    }
}
