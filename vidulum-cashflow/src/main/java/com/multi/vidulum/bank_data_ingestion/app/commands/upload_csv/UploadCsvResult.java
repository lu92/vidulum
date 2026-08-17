package com.multi.vidulum.bank_data_ingestion.app.commands.upload_csv;

import com.multi.vidulum.bank_data_ingestion.app.CsvParserService;
import com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions.StageTransactionsResult;

import java.util.List;

/**
 * Result of uploading and staging a CSV file.
 * Contains both CSV parsing status and staging result.
 *
 * @param parseResult    result of parsing the CSV (rows parsed, errors)
 * @param stagingResult  result of staging transactions (null if parsing failed completely)
 */
public record UploadCsvResult(
        ParseSummary parseResult,
        StageTransactionsResult stagingResult
) {

    /**
     * Summary of CSV parsing.
     */
    public record ParseSummary(
            int totalRows,
            int successfulRows,
            int failedRows,
            List<ParseError> errors
    ) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /**
     * Error from CSV parsing.
     */
    public record ParseError(
            int rowNumber,
            String message
    ) {
        public static ParseError from(CsvParserService.CsvParseError error) {
            return new ParseError(error.rowNumber(), error.message());
        }
    }
}
