package com.multi.vidulum.bank_data_ingestion.app.commands.upload_csv;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import org.springframework.web.multipart.MultipartFile;

/**
 * Command to upload and stage a CSV file containing bank transactions.
 * The CSV file must be in BankCsvRow format (normalized format).
 *
 * @param cashFlowId the CashFlow to stage transactions for
 * @param csvFile    the CSV file in BankCsvRow format
 * @param metadata   optional metadata from AI transformation (nullable)
 */
public record UploadCsvCommand(
        CashFlowId cashFlowId,
        MultipartFile csvFile,
        SessionMetadata metadata
) implements Command {

    /**
     * Constructor without metadata (for backward compatibility with direct uploads).
     */
    public UploadCsvCommand(CashFlowId cashFlowId, MultipartFile csvFile) {
        this(cashFlowId, csvFile, null);
    }

    /**
     * Metadata from AI transformation that created this upload.
     *
     * @param transformationId  ID of the AI transformation (nullable)
     * @param detectedLanguage  language detected by AI (e.g., "pl", "en", "de")
     * @param detectedBank      bank detected by AI (e.g., "Nest Bank", "PKO BP")
     * @param detectedCountry   country detected from bank or IBAN (e.g., "PL", "DE")
     * @param originalFileName  original uploaded file name
     * @param createdByUserId   user ID who created this session
     */
    public record SessionMetadata(
            String transformationId,
            String detectedLanguage,
            String detectedBank,
            String detectedCountry,
            String originalFileName,
            String createdByUserId
    ) {}
}
