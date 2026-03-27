package com.multi.vidulum.bank_data_adapter.rest;

import com.multi.vidulum.bank_data_adapter.app.AiBankCsvTransformService;
import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationDocument;
import com.multi.vidulum.user.domain.DomainUserRepository;
import com.multi.vidulum.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Unified CSV Import Controller.
 *
 * Single endpoint for all CSV uploads that:
 * 1. Auto-detects format (CANONICAL, CACHED, AI_TRANSFORMED)
 * 2. Returns transformation details with suggestedStartPeriod
 * 3. Provides all info needed for CashFlow creation
 *
 * This simplifies UI integration - one endpoint handles:
 * - Vidulum canonical format CSV (instant)
 * - Previously seen bank formats (instant, from cache)
 * - New bank formats (AI transformation, 5-15s)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/csv-import")
@RequiredArgsConstructor
public class UnifiedCsvImportController {

    private final AiBankCsvTransformService transformService;
    private final DomainUserRepository userRepository;

    /**
     * Upload and process a CSV file.
     *
     * Automatically detects format and returns processing info.
     * Response includes detectionResult to inform UI how file was processed.
     *
     * @param file CSV file (max 5MB)
     * @param bankHint Optional bank name hint to speed up detection
     * @return UploadResponse with transformation details
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "bankHint", required = false) String bankHint) throws IOException {

        String userId = getCurrentUserId();
        log.info("Unified CSV upload: file={}, size={}, bankHint={}, userId={}",
            file.getOriginalFilename(), file.getSize(), bankHint, userId);

        AiCsvTransformationDocument result = transformService.transform(
            file.getBytes(),
            file.getOriginalFilename(),
            bankHint,
            userId
        );

        UploadResponse response = toUploadResponse(result);

        log.info("Upload processed: id={}, detection={}, time={}ms, rows={}",
            result.getId(), result.getDetectionResult(),
            result.getProcessingTimeMs(), result.getOutputRowCount());

        return ResponseEntity.ok(response);
    }

    // ========== Helper Methods ==========

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

    // ========== Response DTOs ==========

    /**
     * Response for unified CSV upload.
     * Contains all information needed for UI to proceed with CashFlow creation.
     */
    public record UploadResponse(
        // Identification
        String transformationId,
        boolean success,

        // Detection info (for UI feedback)
        String detectionResult,         // CANONICAL, CACHED, AI_TRANSFORMED
        boolean fromCache,              // Whether cached mapping rules were used
        long processingTimeMs,          // Processing time in milliseconds

        // Bank info (for CashFlow creation form)
        String detectedBank,            // Bank name to pre-fill
        String detectedCurrency,        // Currency to pre-fill (from transactions)
        String detectedLanguage,        // Detected language
        String detectedCountry,         // Detected country

        // Transaction data
        int rowCount,                   // Number of transactions
        List<String> warnings,          // Any warnings from processing

        // Date range (CRITICAL for CashFlow startPeriod)
        String minTransactionDate,      // Earliest transaction date (YYYY-MM-DD)
        String maxTransactionDate,      // Latest transaction date (YYYY-MM-DD)
        String suggestedStartPeriod,    // YearMonth string (YYYY-MM) - USE THIS FOR STARTPERIOD!
        int monthsOfData,               // Number of distinct months covered
        List<String> monthsCovered,     // List of "YYYY-MM" strings in order

        // Bank categories preview (for mapping step)
        List<BankCategoryPreview> bankCategories,

        // Import status
        String importStatus,            // PENDING, IMPORTED, etc.

        // Error info (if any)
        String errorCode,
        String errorMessage
    ) {}

    /**
     * Preview of bank categories found in CSV.
     */
    public record BankCategoryPreview(
        String name,
        int count,
        String type    // INFLOW or OUTFLOW
    ) {}

    // ========== Mapping helpers ==========

    private UploadResponse toUploadResponse(AiCsvTransformationDocument doc) {
        // Extract bank categories from transformed CSV
        List<BankCategoryPreview> categories = extractBankCategories(doc.getTransformedCsvContent());

        // Detect currency from first transaction
        String detectedCurrency = extractCurrencyFromCsv(doc.getTransformedCsvContent());

        return new UploadResponse(
            doc.getId(),
            doc.isSuccess(),
            // Detection info
            doc.getDetectionResult() != null ? doc.getDetectionResult().name() : null,
            doc.isFromCache(),
            doc.getProcessingTimeMs(),
            // Bank info
            doc.getDetectedBank(),
            detectedCurrency,
            doc.getDetectedLanguage(),
            doc.getDetectedCountry(),
            // Transaction data
            doc.getOutputRowCount(),
            doc.getWarnings() != null ? doc.getWarnings() : List.of(),
            // Date range
            doc.getMinTransactionDate() != null ? doc.getMinTransactionDate().toString() : null,
            doc.getMaxTransactionDate() != null ? doc.getMaxTransactionDate().toString() : null,
            doc.getSuggestedStartPeriod(),
            doc.getMonthsOfData(),
            doc.getMonthsCovered() != null ? doc.getMonthsCovered() : List.of(),
            // Bank categories
            categories,
            // Import status
            doc.getImportStatus() != null ? doc.getImportStatus().name() : null,
            // Errors
            doc.getErrorCode(),
            doc.getErrorMessage()
        );
    }

    /**
     * Extracts bank categories from transformed CSV content.
     * Groups by bankCategory column and counts occurrences.
     */
    private List<BankCategoryPreview> extractBankCategories(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            return List.of();
        }

        String[] lines = csvContent.split("\n");
        if (lines.length < 2) {
            return List.of();
        }

        // Find column indices
        String[] headers = parseCSVLine(lines[0]);
        int categoryIndex = -1;
        int typeIndex = -1;

        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase();
            if (h.equals("bankcategory")) {
                categoryIndex = i;
            } else if (h.equals("type")) {
                typeIndex = i;
            }
        }

        if (categoryIndex == -1) {
            return List.of();
        }

        // Count categories
        java.util.Map<String, int[]> categoryCounts = new java.util.HashMap<>(); // [count, inflowCount]

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] values = parseCSVLine(line);
            if (values.length > categoryIndex) {
                String category = values[categoryIndex].trim();
                if (!category.isEmpty()) {
                    String type = typeIndex >= 0 && values.length > typeIndex ?
                        values[typeIndex].trim().toUpperCase() : "OUTFLOW";

                    categoryCounts.computeIfAbsent(category, k -> new int[]{0, 0});
                    categoryCounts.get(category)[0]++;
                    if ("INFLOW".equals(type)) {
                        categoryCounts.get(category)[1]++;
                    }
                }
            }
        }

        // Convert to preview list
        return categoryCounts.entrySet().stream()
            .map(e -> new BankCategoryPreview(
                e.getKey(),
                e.getValue()[0],
                e.getValue()[1] > e.getValue()[0] / 2 ? "INFLOW" : "OUTFLOW"
            ))
            .sorted((a, b) -> Integer.compare(b.count(), a.count()))
            .toList();
    }

    /**
     * Extracts currency from first transaction in CSV.
     */
    private String extractCurrencyFromCsv(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            return null;
        }

        String[] lines = csvContent.split("\n");
        if (lines.length < 2) {
            return null;
        }

        String[] headers = parseCSVLine(lines[0]);
        int currencyIndex = -1;

        for (int i = 0; i < headers.length; i++) {
            if (headers[i].trim().equalsIgnoreCase("currency")) {
                currencyIndex = i;
                break;
            }
        }

        if (currencyIndex == -1) {
            return null;
        }

        // Get currency from first data row
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] values = parseCSVLine(line);
            if (values.length > currencyIndex) {
                String currency = values[currencyIndex].trim();
                if (!currency.isEmpty()) {
                    return currency;
                }
            }
        }

        return null;
    }

    /**
     * Simple CSV line parser that handles quoted fields.
     */
    private String[] parseCSVLine(String line) {
        java.util.List<String> result = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());

        return result.toArray(new String[0]);
    }
}
