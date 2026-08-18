package com.multi.vidulum.bank_data_adapter.app;

import com.multi.vidulum.bank_data_adapter.domain.MappingRules;
import com.multi.vidulum.bank_data_adapter.domain.MappingRulesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Service for caching and retrieving mapping rules.
 *
 * The cache key (bankIdentifier) is computed from the CSV structure:
 * - Header row columns
 * - Metadata structure (first few lines)
 *
 * This allows us to recognize the same bank format without AI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingRulesCacheService {

    private final MappingRulesRepository mappingRulesRepository;

    /**
     * Computes a unique identifier for a bank CSV format.
     *
     * @param csvContent Full CSV content
     * @return Bank identifier hash
     */
    public String computeBankIdentifier(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            return null;
        }

        // Extract structure signature: first 10 lines + header detection
        String[] lines = csvContent.split("\n");
        StringBuilder signature = new StringBuilder();

        int metadataLines = 0;
        String headerRow = null;

        for (int i = 0; i < Math.min(lines.length, 15); i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            // Detect header row
            if (headerRow == null && isLikelyHeader(line)) {
                headerRow = normalizeHeader(line);
                signature.append("HEADER:").append(headerRow).append("\n");
                metadataLines = i;
                continue;
            }

            // Collect metadata structure (column count, patterns)
            if (headerRow == null) {
                // Metadata line - extract structure, not content
                signature.append("META:").append(extractStructure(line)).append("\n");
            }
        }

        // If no header found, use first line as header
        if (headerRow == null && lines.length > 0) {
            headerRow = normalizeHeader(lines[0]);
            signature.append("HEADER:").append(headerRow).append("\n");
        }

        return computeHash(signature.toString());
    }

    /**
     * Finds cached mapping rules for a bank identifier.
     *
     * @param bankIdentifier Bank format identifier
     * @return Optional mapping rules
     */
    public Optional<MappingRules> findByBankIdentifier(String bankIdentifier) {
        if (bankIdentifier == null) {
            return Optional.empty();
        }

        Optional<MappingRules> rules = mappingRulesRepository.findByBankIdentifier(bankIdentifier);

        if (rules.isPresent()) {
            log.info("Found cached mapping rules for bank: {} ({})",
                rules.get().getBankName(), bankIdentifier.substring(0, 8));
        }

        return rules;
    }

    /**
     * Finds cached mapping rules for CSV content.
     *
     * @param csvContent CSV content
     * @return Optional mapping rules
     */
    public Optional<MappingRules> findForCsv(String csvContent) {
        String bankIdentifier = computeBankIdentifier(csvContent);
        return findByBankIdentifier(bankIdentifier);
    }

    /**
     * Saves new mapping rules to cache.
     *
     * @param rules Mapping rules to save
     * @return Saved rules
     */
    public MappingRules save(MappingRules rules) {
        rules.setCreatedAt(new Date());
        MappingRules saved = mappingRulesRepository.save(rules);
        log.info("Saved mapping rules for bank: {} ({})",
            saved.getBankName(), saved.getBankIdentifier().substring(0, 8));
        return saved;
    }

    /**
     * Records usage of mapping rules (increments counter and updates timestamp).
     *
     * @param bankIdentifier Bank identifier
     */
    public void recordUsage(String bankIdentifier) {
        mappingRulesRepository.findByBankIdentifier(bankIdentifier)
            .ifPresent(rules -> {
                rules.incrementUsage();
                mappingRulesRepository.save(rules);
                log.debug("Recorded usage for bank: {} (count: {})",
                    rules.getBankName(), rules.getUsageCount());
            });
    }

    /**
     * Lists all cached mapping rules.
     *
     * @return List of all mapping rules, ordered by usage count
     */
    public List<MappingRules> listAll() {
        return mappingRulesRepository.findAllByOrderByUsageCountDesc();
    }

    /**
     * Deletes mapping rules for a bank identifier.
     *
     * @param bankIdentifier Bank identifier to delete
     */
    public void delete(String bankIdentifier) {
        mappingRulesRepository.deleteByBankIdentifier(bankIdentifier);
        log.info("Deleted mapping rules for bank identifier: {}", bankIdentifier);
    }

    /**
     * Checks if mapping rules exist for a bank identifier.
     *
     * @param bankIdentifier Bank identifier
     * @return true if rules exist
     */
    public boolean exists(String bankIdentifier) {
        return bankIdentifier != null && mappingRulesRepository.existsByBankIdentifier(bankIdentifier);
    }

    // ============ Private helpers ============

    private boolean isLikelyHeader(String line) {
        String lower = line.toLowerCase();
        // Polish bank CSV headers
        if (lower.contains("data") && (
            lower.contains("kwota") ||
            lower.contains("operacj") ||
            lower.contains("saldo") ||
            lower.contains("tytuł") ||
            lower.contains("tytul") ||
            lower.contains("kontrahent")
        )) {
            return true;
        }
        // English headers
        if (lower.contains("date") && (
            lower.contains("amount") ||
            lower.contains("description") ||
            lower.contains("balance") ||
            lower.contains("transaction")
        )) {
            return true;
        }
        return false;
    }

    private String normalizeHeader(String header) {
        // Normalize header for consistent matching
        return header.toLowerCase()
            .replaceAll("\\s+", " ")
            .replaceAll("[^a-ząćęłńóśźża-z0-9,;\\t|]", "")
            .trim();
    }

    private String extractStructure(String line) {
        // Extract structural info without sensitive content
        // Count columns, detect separators
        int commaCount = countChar(line, ',');
        int semicolonCount = countChar(line, ';');
        int tabCount = countChar(line, '\t');
        int pipeCount = countChar(line, '|');

        char separator = ',';
        int maxCount = commaCount;
        if (semicolonCount > maxCount) {
            separator = ';';
            maxCount = semicolonCount;
        }
        if (tabCount > maxCount) {
            separator = '\t';
            maxCount = tabCount;
        }
        if (pipeCount > maxCount) {
            separator = '|';
        }

        return String.format("cols=%d,sep=%c,len=%d",
            maxCount + 1, separator, line.length() / 10 * 10);
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (char ch : s.toCharArray()) {
            if (ch == c) count++;
        }
        return count;
    }

    private String computeHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return "bank:" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
