package com.multi.vidulum.bank_data_adapter.rest;

import com.multi.vidulum.bank_data_adapter.app.AiBankCsvTransformService;
import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationDocument;
import com.multi.vidulum.bank_data_adapter.infrastructure.BankDataIngestionClient;
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
 * REST controller for AI Bank CSV Adapter.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/bank-data-adapter")
@RequiredArgsConstructor
public class AiBankCsvController {

    private final AiBankCsvTransformService transformService;
    private final BankDataIngestionClient ingestionClient;
    private final DomainUserRepository userRepository;

    /**
     * Upload and transform a bank CSV file using AI.
     */
    @PostMapping(value = "/transform", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TransformResponse> transform(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "bankHint", required = false) String bankHint) throws IOException {

        String userId = getCurrentUserId();
        log.info("Transform request: file={}, size={}, bankHint={}, userId={}",
            file.getOriginalFilename(), file.getSize(), bankHint, userId);

        AiCsvTransformationDocument result = transformService.transform(
            file.getBytes(),
            file.getOriginalFilename(),
            bankHint,
            userId
        );

        return ResponseEntity.ok(toTransformResponse(result));
    }

    /**
     * Get transformation details by ID.
     */
    @GetMapping("/{transformationId}")
    public ResponseEntity<TransformResponse> getTransformation(
            @PathVariable String transformationId) {

        String userId = getCurrentUserId();
        AiCsvTransformationDocument doc = transformService.getTransformation(transformationId, userId);
        return ResponseEntity.ok(toTransformResponse(doc));
    }

    /**
     * Get preview of transformed CSV (first 10 rows).
     */
    @GetMapping("/{transformationId}/preview")
    public ResponseEntity<PreviewResponse> getPreview(
            @PathVariable String transformationId) {

        String userId = getCurrentUserId();
        AiCsvTransformationDocument doc = transformService.getTransformation(transformationId, userId);

        List<String> previewLines = extractPreviewLines(doc.getTransformedCsvContent(), 10);

        return ResponseEntity.ok(new PreviewResponse(
            doc.getId(),
            doc.getDetectedBank(),
            doc.getOutputRowCount(),
            previewLines
        ));
    }

    /**
     * Download the transformed CSV file.
     */
    @GetMapping(value = "/{transformationId}/download", produces = "text/csv")
    public ResponseEntity<String> downloadCsv(
            @PathVariable String transformationId) {

        String userId = getCurrentUserId();
        AiCsvTransformationDocument doc = transformService.getTransformation(transformationId, userId);

        String filename = String.format("transformed_%s_%s.csv",
            doc.getDetectedBank() != null ? doc.getDetectedBank().toLowerCase() : "bank",
            doc.getId().substring(0, 8));

        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .body(doc.getTransformedCsvContent());
    }

    /**
     * Import the transformed CSV to bank-data-ingestion.
     */
    @PostMapping("/{transformationId}/import")
    public ResponseEntity<ImportResponse> importToCashFlow(
            @PathVariable String transformationId,
            @RequestBody ImportRequest request,
            @RequestHeader("Authorization") String authHeader) {

        String userId = getCurrentUserId();
        log.info("Import request: transformationId={}, cashFlowId={}, userId={}",
            transformationId, request.cashFlowId(), userId);

        // Get transformation
        AiCsvTransformationDocument doc = transformService.getTransformation(transformationId, userId);

        // Build metadata from transformation document
        BankDataIngestionClient.TransformationMetadata metadata = new BankDataIngestionClient.TransformationMetadata(
            doc.getId(),
            doc.getDetectedLanguage(),
            doc.getDetectedBank(),
            doc.getDetectedCountry(),
            doc.getOriginalFileName(),
            userId
        );

        // Send to ingestion with metadata
        String fileName = String.format("ai_transformed_%s.csv", transformationId.substring(0, 8));
        BankDataIngestionClient.UploadCsvResponse uploadResponse = ingestionClient.sendToIngestion(
            request.cashFlowId(),
            doc.getTransformedCsvContent(),
            fileName,
            authHeader,
            metadata
        );

        // Extract stagingSessionId from response
        String stagingSessionId = uploadResponse.getStagingSessionId();

        if (stagingSessionId != null) {
            transformService.markAsImported(transformationId, userId, stagingSessionId);
        }

        return ResponseEntity.ok(new ImportResponse(
            transformationId,
            stagingSessionId,
            uploadResponse.parseSummary() != null ? uploadResponse.parseSummary().successfulRows() : doc.getOutputRowCount(),
            "Transformation imported successfully"
        ));
    }

    /**
     * Get user's transformation history.
     */
    @GetMapping("/history")
    public ResponseEntity<List<TransformHistoryItem>> getHistory() {

        String userId = getCurrentUserId();
        List<AiCsvTransformationDocument> transformations = transformService.getUserTransformations(userId);

        List<TransformHistoryItem> items = transformations.stream()
            .map(this::toHistoryItem)
            .toList();

        return ResponseEntity.ok(items);
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

    public record TransformResponse(
        String transformationId,
        boolean success,
        String detectedBank,
        String detectedLanguage,
        String detectedCountry,
        int rowCount,
        List<String> warnings,
        String importStatus,
        String errorCode,
        String errorMessage,
        // Date range fields
        String minTransactionDate,      // Earliest transaction date (YYYY-MM-DD)
        String maxTransactionDate,      // Latest transaction date (YYYY-MM-DD)
        String suggestedStartPeriod,    // YearMonth string (YYYY-MM)
        int monthsOfData,               // Number of distinct months covered
        List<String> monthsCovered,     // List of "YYYY-MM" strings in order
        // Detection info (for UI feedback)
        String detectionResult,         // CANONICAL, CACHED, AI_TRANSFORMED
        boolean fromCache,              // Whether cached mapping rules were used
        long processingTimeMs,          // Processing time in milliseconds
        // Enrichment stats (Phase 2: merchant extraction + bankCategory inference)
        boolean enrichmentApplied,      // Whether enrichment was performed
        int merchantsExtracted,         // Number of merchants extracted by AI
        int bankCategoriesInferred,     // Number of bankCategories inferred by AI (for banks without categories)
        int bankCategoriesKept,         // Number of original bankCategories kept
        long enrichmentTimeMs,          // Time spent on enrichment in milliseconds
        int enrichmentAiCalls           // Number of AI calls made for enrichment
    ) {}

    public record PreviewResponse(
        String transformationId,
        String detectedBank,
        int totalRows,
        List<String> previewLines
    ) {}

    public record ImportRequest(
        String cashFlowId
    ) {}

    public record ImportResponse(
        String transformationId,
        String stagingSessionId,
        int importedRows,
        String message
    ) {}

    public record TransformHistoryItem(
        String transformationId,
        String originalFileName,
        String detectedBank,
        int rowCount,
        boolean success,
        String importStatus,
        String createdAt
    ) {}

    // ========== Mapping helpers ==========

    private TransformResponse toTransformResponse(AiCsvTransformationDocument doc) {
        return new TransformResponse(
            doc.getId(),
            doc.isSuccess(),
            doc.getDetectedBank(),
            doc.getDetectedLanguage(),
            doc.getDetectedCountry(),
            doc.getOutputRowCount(),
            doc.getWarnings() != null ? doc.getWarnings() : List.of(),
            doc.getImportStatus() != null ? doc.getImportStatus().name() : null,
            doc.getErrorCode(),
            doc.getErrorMessage(),
            // Date range fields
            doc.getMinTransactionDate() != null ? doc.getMinTransactionDate().toString() : null,
            doc.getMaxTransactionDate() != null ? doc.getMaxTransactionDate().toString() : null,
            doc.getSuggestedStartPeriod(),
            doc.getMonthsOfData(),
            doc.getMonthsCovered() != null ? doc.getMonthsCovered() : List.of(),
            // Detection info
            doc.getDetectionResult() != null ? doc.getDetectionResult().name() : null,
            doc.isFromCache(),
            doc.getProcessingTimeMs(),
            // Enrichment stats
            doc.isEnrichmentApplied(),
            doc.getMerchantsExtracted(),
            doc.getBankCategoriesInferred(),
            doc.getBankCategoriesKept(),
            doc.getEnrichmentTimeMs(),
            doc.getEnrichmentAiCalls()
        );
    }

    private TransformHistoryItem toHistoryItem(AiCsvTransformationDocument doc) {
        return new TransformHistoryItem(
            doc.getId(),
            doc.getOriginalFileName(),
            doc.getDetectedBank(),
            doc.getOutputRowCount(),
            doc.isSuccess(),
            doc.getImportStatus() != null ? doc.getImportStatus().name() : null,
            doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null
        );
    }

    private List<String> extractPreviewLines(String csvContent, int maxLines) {
        if (csvContent == null || csvContent.isBlank()) {
            return List.of();
        }

        String[] lines = csvContent.split("\n");
        int count = Math.min(lines.length, maxLines + 1); // +1 for header

        return List.of(lines).subList(0, count);
    }
}
