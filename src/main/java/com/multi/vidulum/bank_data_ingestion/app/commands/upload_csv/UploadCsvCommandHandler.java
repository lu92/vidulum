package com.multi.vidulum.bank_data_ingestion.app.commands.upload_csv;

import com.multi.vidulum.bank_data_ingestion.app.CsvParserService;
import com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions.StageTransactionsCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions.StageTransactionsCommandHandler;
import com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions.StageTransactionsResult;
import com.multi.vidulum.bank_data_ingestion.domain.BankCsvRow;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Handler for uploading and staging CSV files with bank transactions.
 * Parses CSV into BankCsvRow format and delegates to StageTransactionsCommandHandler directly.
 */
@Slf4j
@Component
@AllArgsConstructor
public class UploadCsvCommandHandler
        implements CommandHandler<UploadCsvCommand, UploadCsvResult> {

    private final CsvParserService csvParserService;
    private final StageTransactionsCommandHandler stageTransactionsCommandHandler;

    @Override
    public UploadCsvResult handle(UploadCsvCommand command) {
        log.info("Processing CSV upload for CashFlow [{}]", command.cashFlowId().id());

        // Parse CSV file
        CsvParserService.CsvParseResult parseResult = csvParserService.parse(command.csvFile());

        // Build parse summary
        UploadCsvResult.ParseSummary parseSummary = new UploadCsvResult.ParseSummary(
                parseResult.totalRows(),
                parseResult.rows().size(),
                parseResult.errors().size(),
                parseResult.errors().stream()
                        .map(UploadCsvResult.ParseError::from)
                        .toList()
        );

        // If no rows were parsed successfully, return early
        if (parseResult.rows().isEmpty()) {
            log.warn("No valid rows parsed from CSV for CashFlow [{}]", command.cashFlowId().id());
            return new UploadCsvResult(parseSummary, null);
        }

        // Convert BankCsvRow to StageTransactionsCommand.BankTransaction
        List<StageTransactionsCommand.BankTransaction> transactions = parseResult.rows().stream()
                .map(this::toBankTransaction)
                .toList();

        // Delegate directly to StageTransactionsCommandHandler to avoid circular dependency
        StageTransactionsResult stagingResult = stageTransactionsCommandHandler.handle(
                new StageTransactionsCommand(command.cashFlowId(), transactions)
        );

        log.info("CSV upload completed for CashFlow [{}]: {} rows parsed, {} staged",
                command.cashFlowId().id(), parseResult.rows().size(),
                stagingResult.summary().totalTransactions());

        return new UploadCsvResult(parseSummary, stagingResult);
    }

    private StageTransactionsCommand.BankTransaction toBankTransaction(BankCsvRow row) {
        // Generate bankTransactionId if not provided
        String bankTransactionId = row.bankTransactionId();
        if (bankTransactionId == null || bankTransactionId.isBlank()) {
            bankTransactionId = generateTransactionId(row);
        }

        // Convert LocalDate to ZonedDateTime (using operationDate, at start of day UTC)
        ZonedDateTime paidDate = row.operationDate().atStartOfDay(ZoneId.of("UTC"));

        return new StageTransactionsCommand.BankTransaction(
                bankTransactionId,
                row.name(),
                row.effectiveDescription(),
                row.effectiveBankCategory(),
                Money.of(row.amount().doubleValue(), row.currency()),
                row.type(),
                paidDate
        );
    }

    /**
     * Generates a unique transaction ID based on transaction data.
     * Format: hash(operationDate + amount + name)
     */
    private String generateTransactionId(BankCsvRow row) {
        String data = String.format("%s|%s|%s|%s",
                row.operationDate(),
                row.amount(),
                row.name(),
                row.type()
        );
        return "gen-" + Integer.toHexString(data.hashCode());
    }
}
