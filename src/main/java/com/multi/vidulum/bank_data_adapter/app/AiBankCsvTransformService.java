package com.multi.vidulum.bank_data_adapter.app;

import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationDocument;
import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationRepository;
import com.multi.vidulum.bank_data_adapter.domain.ImportStatus;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules;
import com.multi.vidulum.bank_data_adapter.domain.exceptions.*;
import com.multi.vidulum.bank_data_adapter.infrastructure.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Main service for AI-powered bank CSV transformation.
 *
 * Architecture:
 * 1. Check if we have cached mapping rules for this bank format
 * 2. If cache hit: use LocalCsvTransformer (no AI call, instant, free)
 * 3. If cache miss: send ANONYMIZED SAMPLE to AI, get mapping rules, cache them
 * 4. Transform full CSV locally using the rules
 *
 * Benefits:
 * - Privacy: Only anonymized sample (10 rows) sent to AI
 * - Cost: First file ~$0.01-0.02, subsequent files FREE
 * - Speed: Cached transformations are instant
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBankCsvTransformService {

    private final ChatModel chatModel;
    private final AiPromptBuilder promptBuilder;
    private final AiResponseProcessor responseProcessor;
    private final AiMappingRulesPromptBuilder mappingRulesPromptBuilder;
    private final AiMappingRulesProcessor mappingRulesProcessor;
    private final CsvAnonymizer csvAnonymizer;
    private final LocalCsvTransformer localCsvTransformer;
    private final MappingRulesCacheService mappingRulesCacheService;
    private final AiCsvTransformationRepository transformationRepository;
    private final Clock clock;

    @Value("${bank-data-adapter.max-file-size-bytes:5242880}")
    private long maxFileSizeBytes; // 5MB default

    @Value("${bank-data-adapter.max-retries:2}")
    private int maxRetries;

    @Value("${bank-data-adapter.sample-rows:10}")
    private int sampleRows;

    @Value("${bank-data-adapter.use-cache:true}")
    private boolean useCache;

    // Pricing per 1M tokens (Haiku)
    private static final BigDecimal INPUT_PRICE_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal OUTPUT_PRICE_PER_MILLION = new BigDecimal("4.00");

    /**
     * Transform a bank CSV file.
     *
     * Flow:
     * 1. Validate file and check for duplicates
     * 2. Compute bank identifier from CSV structure
     * 3. Check cache for mapping rules
     * 4. If cache miss: call AI with anonymized sample
     * 5. Transform full CSV locally using rules
     * 6. Save and return result
     *
     * @param csvContent  Raw CSV content as bytes
     * @param fileName    Original file name
     * @param bankHint    Optional hint about the bank
     * @param userId      User ID for audit
     * @return The created transformation document
     */
    public AiCsvTransformationDocument transform(byte[] csvContent, String fileName, String bankHint, String userId) {
        // Validate file
        validateFile(csvContent, fileName);

        // Calculate hash for deduplication
        String fileHash = calculateHash(csvContent);

        // Check for duplicate
        transformationRepository.findByOriginalFileHashAndUserId(fileHash, userId)
            .ifPresent(existing -> {
                throw new DuplicateFileException(fileHash, existing.getId());
            });

        // Decode CSV content
        String csvString = decodeWithFallback(csvContent);

        // Compute bank identifier
        String bankIdentifier = mappingRulesCacheService.computeBankIdentifier(csvString);
        log.info("Bank identifier: {}", bankIdentifier != null ? bankIdentifier.substring(0, 12) : "null");

        // Create initial document
        AiCsvTransformationDocument document = AiCsvTransformationDocument.builder()
            .id(UUID.randomUUID().toString())
            .userId(userId)
            .originalFileName(fileName)
            .originalFileSizeBytes(csvContent.length)
            .originalFileHash(fileHash)
            .originalCsvContent(csvString)
            .bankHint(bankHint)
            .createdAt(AiCsvTransformationDocument.toDate(ZonedDateTime.now(clock)))
            .createdBy(userId)
            .importStatus(ImportStatus.PENDING)
            .build();

        long startTime = System.currentTimeMillis();
        boolean fromCache = false;
        MappingRules rules = null;

        // Step 1: Check cache
        if (useCache && bankIdentifier != null) {
            Optional<MappingRules> cachedRules = mappingRulesCacheService.findByBankIdentifier(bankIdentifier);
            if (cachedRules.isPresent()) {
                rules = cachedRules.get();
                fromCache = true;
                log.info("✅ Cache HIT for bank: {} (usage: {})",
                    rules.getBankName(), rules.getUsageCount());
                mappingRulesCacheService.recordUsage(bankIdentifier);
            }
        }

        // Step 2: If no cache, try AI-based transformation
        if (rules == null) {
            log.info("❌ Cache MISS - calling AI for mapping rules");
            rules = obtainMappingRulesFromAi(csvString, bankHint, bankIdentifier, userId);
        }

        // Step 3: Transform using rules (or fallback to direct AI)
        String transformedCsv;
        int rowCount;
        List<String> warnings = new ArrayList<>();

        if (rules != null) {
            // Use local transformer
            LocalCsvTransformer.TransformResult localResult = localCsvTransformer.transform(csvString, rules);
            if (localResult.success()) {
                transformedCsv = localResult.csvContent();
                rowCount = localResult.rowCount();
                warnings.addAll(localResult.warnings());
                document.setDetectedBank(rules.getBankName());
                document.setDetectedLanguage(rules.getLanguage());
                document.setDetectedCountry(rules.getBankCountry());
            } else {
                // Local transform failed - fallback to direct AI
                log.warn("Local transform failed: {}, falling back to direct AI", localResult.errorMessage());
                return transformWithDirectAi(document, csvString, bankHint, startTime);
            }
        } else {
            // No rules available - use direct AI
            return transformWithDirectAi(document, csvString, bankHint, startTime);
        }

        // Complete document
        long processingTime = System.currentTimeMillis() - startTime;
        document.setProcessingTimeMs(processingTime);
        document.setSuccess(true);
        document.setTransformedCsvContent(transformedCsv);
        document.setOutputRowCount(rowCount);
        document.setWarnings(warnings);
        document.setInputRowCount(countInputRows(csvString));
        document.setFromCache(fromCache);
        document.setBankIdentifier(bankIdentifier);

        // Save document
        AiCsvTransformationDocument saved = transformationRepository.save(document);

        log.info("Transformation completed: id={}, success=true, bank={}, rows={}, time={}ms, fromCache={}",
            saved.getId(), saved.getDetectedBank(), saved.getOutputRowCount(),
            saved.getProcessingTimeMs(), fromCache);

        return saved;
    }

    /**
     * Obtains mapping rules from AI using anonymized sample.
     */
    private MappingRules obtainMappingRulesFromAi(String csvContent, String bankHint,
                                                   String bankIdentifier, String userId) {
        // Anonymize and extract sample
        String anonymizedSample = csvAnonymizer.anonymizeAndSample(csvContent, sampleRows);
        log.debug("Anonymized sample size: {} chars (original: {} chars)",
            anonymizedSample.length(), csvContent.length());

        // Call AI for mapping rules
        int retryCount = 0;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String systemPrompt = mappingRulesPromptBuilder.getSystemPrompt();
                String userPrompt = mappingRulesPromptBuilder.buildUserPrompt(anonymizedSample, bankHint);

                Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
                ));

                ChatResponse response = callAiWithErrorHandling(prompt);
                String aiOutput = response.getResult().getOutput().getText();

                AiMappingRulesProcessor.MappingRulesResult result =
                    mappingRulesProcessor.process(aiOutput, bankIdentifier);

                if (result.success()) {
                    MappingRules rules = result.rules();
                    rules.setCreatedByUserId(userId);
                    rules.setGeneratedByModel("claude-haiku");
                    rules.setPromptVersion("v1");

                    // Cache the rules
                    if (useCache && bankIdentifier != null) {
                        mappingRulesCacheService.save(rules);
                        log.info("Cached new mapping rules for bank: {}", rules.getBankName());
                    }

                    return rules;
                } else {
                    log.warn("AI returned error for mapping rules: {}", result.errorMessage());
                }

            } catch (AiRateLimitExceededException | AiServiceUnavailableException e) {
                // Don't retry these errors
                throw e;
            } catch (Exception e) {
                lastException = e;
                retryCount = attempt;
                log.error("AI mapping rules error on attempt {}", attempt, e);

                if (attempt == maxRetries) {
                    break;
                }
            }
        }

        log.warn("Failed to obtain mapping rules from AI after {} attempts", retryCount);
        return null; // Fallback to direct AI transformation
    }

    /**
     * Fallback: Transform using direct AI call (full CSV, no cache).
     */
    private AiCsvTransformationDocument transformWithDirectAi(
            AiCsvTransformationDocument document, String csvContent, String bankHint, long startTime) {

        log.info("Using direct AI transformation (no cache)");

        int retryCount = 0;
        AiTransformResult result = null;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                result = callAiAndProcess(csvContent, bankHint);

                if (result.success()) {
                    break;
                }

                // If AI returned structured error, don't retry
                if (result.error() != null &&
                    result.error().code() == AiErrorCode.UNRECOGNIZED_FORMAT) {
                    break;
                }

                retryCount = attempt;
                log.warn("AI transform attempt {} failed, retrying...", attempt);

            } catch (AiRateLimitExceededException | AiServiceUnavailableException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
                retryCount = attempt;
                log.error("AI transform error on attempt {}", attempt, e);

                if (attempt == maxRetries) {
                    break;
                }
            }
        }

        long processingTime = System.currentTimeMillis() - startTime;

        // Update document with results
        document.setProcessingTimeMs(processingTime);
        document.setRetryCount(retryCount);
        document.setFromCache(false);

        if (result != null && result.success()) {
            document.setSuccess(true);
            document.setTransformedCsvContent(result.csvContent());
            document.setDetectedBank(result.detectedBank());
            document.setDetectedLanguage(result.detectedLanguage());
            document.setDetectedCountry(result.detectedCountry());
            document.setOutputRowCount(result.rowCount());
            document.setWarnings(result.warnings());
            document.setInputRowCount(countInputRows(csvContent));
        } else {
            document.setSuccess(false);
            if (result != null && result.error() != null) {
                document.setErrorCode(result.error().code().name());
                document.setErrorMessage(result.error().message());
            } else if (lastException != null) {
                document.setErrorCode(AiErrorCode.AI_SERVICE_ERROR.name());
                document.setErrorMessage(lastException.getMessage());
            }
        }

        // Save document
        AiCsvTransformationDocument saved = transformationRepository.save(document);

        log.info("Direct AI transformation completed: id={}, success={}, bank={}, rows={}, time={}ms",
            saved.getId(), saved.isSuccess(), saved.getDetectedBank(),
            saved.getOutputRowCount(), saved.getProcessingTimeMs());

        // If transformation failed, throw exception
        if (!saved.isSuccess()) {
            if (result != null && result.error() != null) {
                throw new UnrecognizedCsvFormatException(
                    extractFirstLine(csvContent),
                    result.error().message()
                );
            } else if (lastException != null) {
                throw new AiServiceException("AI transformation failed", lastException);
            }
        }

        return saved;
    }

    /**
     * Get transformation by ID (only if owned by user).
     */
    public AiCsvTransformationDocument getTransformation(String id, String userId) {
        validateTransformationId(id);
        return transformationRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new TransformationNotFoundException(id));
    }

    /**
     * Get all transformations for a user.
     */
    public List<AiCsvTransformationDocument> getUserTransformations(String userId) {
        return transformationRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Mark transformation as imported.
     */
    public void markAsImported(String transformationId, String userId, String stagingSessionId) {
        AiCsvTransformationDocument doc = getTransformation(transformationId, userId);

        if (doc.getImportStatus() == ImportStatus.IMPORTED) {
            throw new TransformationAlreadyImportedException(transformationId, doc.getStagingSessionId());
        }

        doc.setImportStatus(ImportStatus.IMPORTED);
        doc.setStagingSessionId(stagingSessionId);
        doc.setImportedAtFromZoned(ZonedDateTime.now(clock));

        transformationRepository.save(doc);
    }

    // ============ Private methods ============

    private AiTransformResult callAiAndProcess(String csvContent, String bankHint) {
        String systemPrompt = promptBuilder.getSystemPrompt();
        String userPrompt = promptBuilder.buildUserPrompt(csvContent, bankHint);

        Prompt prompt = new Prompt(List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(userPrompt)
        ));

        ChatResponse response = callAiWithErrorHandling(prompt);
        String aiOutput = response.getResult().getOutput().getText();

        return responseProcessor.process(aiOutput);
    }

    private ChatResponse callAiWithErrorHandling(Prompt prompt) {
        try {
            return chatModel.call(prompt);
        } catch (Exception e) {
            // Check for rate limit errors
            if (isRateLimitError(e)) {
                int retryAfter = extractRetryAfterSeconds(e);
                log.warn("AI rate limit exceeded, retry after {} seconds", retryAfter);
                throw new AiRateLimitExceededException(retryAfter);
            }

            // Check for service unavailable errors
            if (isServiceUnavailableError(e)) {
                log.error("AI service unavailable", e);
                throw new AiServiceUnavailableException("AI service is temporarily unavailable", e);
            }

            // Re-throw other exceptions
            throw e;
        }
    }

    private boolean isRateLimitError(Exception e) {
        String message = getFullExceptionMessage(e);
        return message.contains("429") ||
               message.toLowerCase().contains("rate limit") ||
               message.toLowerCase().contains("too many requests") ||
               message.toLowerCase().contains("quota exceeded");
    }

    private boolean isServiceUnavailableError(Exception e) {
        String message = getFullExceptionMessage(e);
        return message.contains("503") ||
               message.contains("502") ||
               message.toLowerCase().contains("service unavailable") ||
               message.toLowerCase().contains("connection refused") ||
               message.toLowerCase().contains("connection timed out") ||
               message.toLowerCase().contains("connect timed out");
    }

    private int extractRetryAfterSeconds(Exception e) {
        String message = getFullExceptionMessage(e);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)\\s*second");
        java.util.regex.Matcher matcher = pattern.matcher(message.toLowerCase());
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 60;
    }

    private String getFullExceptionMessage(Exception e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        while (current != null) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(" ");
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    private void validateFile(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new EmptyFileException();
        }

        if (content.length > maxFileSizeBytes) {
            throw new FileTooLargeException(content.length, maxFileSizeBytes);
        }

        if (fileName != null && !fileName.toLowerCase().endsWith(".csv")) {
            String extension = fileName.contains(".") ?
                fileName.substring(fileName.lastIndexOf('.')) : "unknown";
            throw new InvalidFileTypeException(extension);
        }
    }

    private void validateTransformationId(String id) {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidTransformationIdFormatException(id);
        }
    }

    private String calculateHash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String decodeWithFallback(byte[] input) {
        // Remove BOM if present
        if (input.length >= 3 && input[0] == (byte) 0xEF &&
            input[1] == (byte) 0xBB && input[2] == (byte) 0xBF) {
            input = Arrays.copyOfRange(input, 3, input.length);
        }

        String content = new String(input, StandardCharsets.UTF_8);

        // Check for encoding issues (replacement character)
        if (content.contains("\uFFFD")) {
            content = new String(input, Charset.forName("CP1250"));
        }

        return content;
    }

    private int countInputRows(String csvContent) {
        String[] lines = csvContent.split("\n");
        int dataRows = 0;
        boolean headerFound = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (!headerFound && (trimmed.contains("Data") || trimmed.contains("Date") ||
                trimmed.contains("Datum") || trimmed.contains("Kwota"))) {
                headerFound = true;
                continue;
            }

            if (headerFound) {
                dataRows++;
            }
        }

        return dataRows > 0 ? dataRows : lines.length - 1;
    }

    private String extractFirstLine(String content) {
        int newlineIndex = content.indexOf('\n');
        if (newlineIndex > 0) {
            return content.substring(0, Math.min(newlineIndex, 200));
        }
        return content.substring(0, Math.min(content.length(), 200));
    }

    private BigDecimal calculateCost(int inputTokens, int outputTokens) {
        BigDecimal inputCost = INPUT_PRICE_PER_MILLION
            .multiply(BigDecimal.valueOf(inputTokens))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);

        BigDecimal outputCost = OUTPUT_PRICE_PER_MILLION
            .multiply(BigDecimal.valueOf(outputTokens))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);

        return inputCost.add(outputCost);
    }
}
