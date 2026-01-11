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
 */
public record UploadCsvCommand(
        CashFlowId cashFlowId,
        MultipartFile csvFile
) implements Command {
}
