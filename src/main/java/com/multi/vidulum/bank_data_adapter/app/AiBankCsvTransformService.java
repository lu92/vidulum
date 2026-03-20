package com.multi.vidulum.bank_data_adapter.app;

import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationDocument;
import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationRepository;
import com.multi.vidulum.bank_data_adapter.domain.ImportStatus;
import com.multi.vidulum.bank_data_adapter.domain.exceptions.*;
import com.multi.vidulum.bank_data_adapter.infrastructure.AiErrorCode;
import com.multi.vidulum.bank_data_adapter.infrastructure.AiPromptBuilder;
import com.multi.vidulum.bank_data_adapter.infrastructure.AiResponseProcessor;
import com.multi.vidulum.bank_data_adapter.infrastructure.AiTransformResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Main service for AI-powered bank CSV transformation.
 * Transforms bank CSV exports to BankCsvRow format using Claude AI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBankCsvTransformService {

    private final AnthropicChatModel chatModel;
    private final AiPromptBuilder promptBuilder;
    private final AiResponseProcessor responseProcessor;
    private final AiCsvTransformationRepository transformationRepository;
    private final Clock clock;

    @Value("${bank-data-adapter.max-file-size-bytes:5242880}")
    private long maxFileSizeBytes; // 5MB default

    @Value("${bank-data-adapter.max-retries:2}")
    private int maxRetries;

    // Pricing per 1M tokens (Haiku)
    private static final BigDecimal INPUT_PRICE_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal OUTPUT_PRICE_PER_MILLION = new BigDecimal("4.00");

    /**
     * Transform a bank CSV file using AI.
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

        // Perform AI transformation
        long startTime = System.currentTimeMillis();
        int retryCount = 0;
        AiTransformResult result = null;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                result = callAiAndProcess(csvString, bankHint);

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

        if (result != null && result.success()) {
            document.setSuccess(true);
            document.setTransformedCsvContent(result.csvContent());
            document.setDetectedBank(result.detectedBank());
            document.setDetectedLanguage(result.detectedLanguage());
            document.setDetectedCountry(result.detectedCountry());
            document.setOutputRowCount(result.rowCount());
            document.setWarnings(result.warnings());
            document.setInputRowCount(countInputRows(csvString));
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

        log.info("Transformation completed: id={}, success={}, bank={}, rows={}, time={}ms",
            saved.getId(), saved.isSuccess(), saved.getDetectedBank(),
            saved.getOutputRowCount(), saved.getProcessingTimeMs());

        // If transformation failed due to unrecognized format, throw exception
        if (!saved.isSuccess()) {
            if (result != null && result.error() != null) {
                throw new UnrecognizedCsvFormatException(
                    extractFirstLine(csvString),
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

    private AiTransformResult callAiAndProcess(String csvContent, String bankHint) {
        String systemPrompt = promptBuilder.getSystemPrompt();
        String userPrompt = promptBuilder.buildUserPrompt(csvContent, bankHint);

        Prompt prompt = new Prompt(List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(userPrompt)
        ));

        ChatResponse response = chatModel.call(prompt);

        String aiOutput = response.getResult().getOutput().getText();

        return responseProcessor.process(aiOutput);
    }

    private void validateFile(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new EmptyFileException();
        }

        if (content.length > maxFileSizeBytes) {
            throw new FileTooLargeException(content.length, maxFileSizeBytes);
        }

        // Check file extension
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

            // Look for header-like row
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
