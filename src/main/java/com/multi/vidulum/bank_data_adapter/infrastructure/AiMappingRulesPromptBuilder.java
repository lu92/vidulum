package com.multi.vidulum.bank_data_adapter.infrastructure;

import org.springframework.stereotype.Component;

/**
 * Builds prompts for AI to generate mapping rules from bank CSV samples.
 *
 * AI analyzes the sample and returns reusable mapping rules.
 * Only delimiter is pre-detected (statistically) and passed as hint.
 * AI determines everything else: headerRowIndex, dateFormat, column mappings.
 */
@Component
public class AiMappingRulesPromptBuilder {

    private static final String SYSTEM_PROMPT = """
        You are a bank CSV format analyzer. Analyze the CSV sample and return mapping rules as JSON.
        Your task is to understand the structure and return rules for LOCAL transformation.
        Return ONLY valid JSON - no markdown, no code blocks, no explanations.

        IMPORTANT: You may receive a pre-detected delimiter value.
        This was detected by statistical analysis of the CSV structure.
        USE THIS VALUE if provided - it's reliable.
        """;

    private static final String USER_PROMPT_TEMPLATE = """
        Analyze this bank CSV sample and return mapping rules to transform it to BankCsvRow format.

        ## DELIMITER HINT
        %s

        ## INPUT CSV SAMPLE (first rows, may be anonymized):
        %s

        ## TARGET FORMAT (BankCsvRow):
        bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber,merchant,merchantConfidence

        ## RETURN JSON with this structure:
        {
          "bankName": "detected bank name",
          "bankCountry": "country code (PL, DE, US, etc.)",
          "language": "language code (pl, de, en, etc.)",
          "dateFormat": "detected date format (dd.MM.yyyy, yyyy-MM-dd, etc.)",
          "delimiter": "detected delimiter (; or , or \\t)",
          "encoding": "UTF-8",
          "headerRowIndex": 0,
          "metadataRows": 0,
          "originalHeader": "actual header from CSV",
          "columnMappings": [
            {
              "sourceColumn": "original column name",
              "sourceIndex": 0,
              "targetField": "target field name",
              "transformationType": "transformation type",
              "transformationParams": {},
              "required": true
            }
          ],
          "confidenceScore": 0.95,
          "warnings": [],
          "sampleInputRow": "example input row",
          "sampleOutputRow": "example output row"
        }

        ## EXAMPLES (showing different bank formats):

        ### Example 1: Polish bank with SEMICOLON delimiter
        CSV sample: "Data księgowania;Data waluty;Nadawca;Kwota operacji;Waluta"
        {
          "bankName": "Bank Pekao",
          "delimiter": ";",
          "headerRowIndex": 0,
          "dateFormat": "dd.MM.yyyy",
          "columnMappings": [...]
        }

        ### Example 2: International bank with COMMA delimiter
        CSV sample: "Date,Description,Amount,Currency"
        {
          "bankName": "Revolut",
          "delimiter": ",",
          "headerRowIndex": 0,
          "dateFormat": "yyyy-MM-dd",
          "columnMappings": [...]
        }

        ### Example 3: Bank with metadata rows before header
        CSV has metadata lines (account info, date range, etc.) before header:
        {
          "bankName": "mBank",
          "delimiter": ";",
          "headerRowIndex": 3,
          "metadataRows": 3,
          "dateFormat": "dd.MM.yyyy",
          "columnMappings": [...]
        }

        ## TRANSFORMATION TYPES:
        - DIRECT: copy value as-is
        - DATE_PARSE: parse date using format in params
        - AMOUNT_PARSE: parse Polish/European number format (1 234,56)
        - TYPE_DETECT: determine INFLOW/OUTFLOW from amount sign or text
        - CURRENCY_EXTRACT: extract currency code
        - IBAN_NORMALIZE: normalize account number to full IBAN
        - CONCAT: combine multiple columns (indices in params)
        - REGEX_EXTRACT: extract part using regex pattern
        - VALUE_MAP: map values using lookup table
        - ID_GENERATE: generate transaction ID
        - SKIP: ignore this column
        - MERCHANT_EXTRACT: extract merchant name from description
        - MERCHANT_CONFIDENCE: calculate confidence score for merchant extraction (0.0-1.0)

        ## RULES FOR MAPPING:
        1. sourceIndex is 0-based column index
        2. All dates must output as YYYY-MM-DD
        3. Amount must be POSITIVE decimal (type handles direction)
        4. Account numbers without country prefix get PL prefix if 26 digits
        5. If no description column, concatenate merchant info columns
        6. bankTransactionId can use ID_GENERATE if bank doesn't provide
        7. CURRENCY IS REQUIRED - always include currency mapping:
           - If CSV has currency column → use CURRENCY_EXTRACT
           - If currency is embedded in amount (e.g., "5000 PLN") → use CURRENCY_EXTRACT with regex
           - If no currency column → use CURRENCY_EXTRACT with default based on country

        8. CRITICAL - TYPE FIELD MAPPING:
           - The "type" field MUST be either "INFLOW" or "OUTFLOW" - never Polish transaction types!
           - ALWAYS use "transformationType": "TYPE_DETECT" for the type field
           - ALWAYS include "amountColumn" parameter pointing to the amount column index
           - The TYPE_DETECT will determine INFLOW/OUTFLOW based on the SIGN of the amount:
             * Negative amounts (-100.00) = OUTFLOW (expenses, payments)
             * Positive amounts (100.00) = INFLOW (income, deposits)
           - NEVER use "DIRECT" for type field
           - Example:
             {
               "sourceColumn": "Kwota",
               "sourceIndex": 3,
               "targetField": "type",
               "transformationType": "TYPE_DETECT",
               "transformationParams": {"amountColumn": "3"},
               "required": true
             }

        9. CRITICAL - NAME FIELD IS REQUIRED:
           - The "name" field is MANDATORY - without it rows will be rejected!
           - Map the counterparty/merchant/recipient column to "name"
           - Common source columns: "Dane kontrahenta", "Kontrahent", "Nadawca/Odbiorca", "Counterparty", "Merchant"
           - If no single name column exists, use CONCAT to combine relevant columns
           - Example:
             {
               "sourceColumn": "Dane kontrahenta",
               "sourceIndex": 5,
               "targetField": "name",
               "transformationType": "DIRECT",
               "transformationParams": {},
               "required": true
             }

        10. DESCRIPTION FIELD:
            - Map transaction title/description to "description"
            - Common source columns: "Tytuł operacji", "Opis", "Tytuł", "Description", "Reference"

        ## REQUIRED MAPPINGS CHECKLIST:
        You MUST include mappings for ALL of these fields:
        - ✓ operationDate (REQUIRED) - use DATE_PARSE
        - ✓ name (REQUIRED) - use DIRECT or CONCAT
        - ✓ amount (REQUIRED) - use AMOUNT_PARSE
        - ✓ currency (REQUIRED) - use CURRENCY_EXTRACT
        - ✓ type (REQUIRED) - use TYPE_DETECT with amountColumn param
        - ○ description (optional) - use DIRECT
        - ○ bankCategory (optional) - use DIRECT
        - ○ bookingDate (optional) - use DATE_PARSE
        - ○ sourceAccountNumber (optional) - use IBAN_NORMALIZE
        - ○ targetAccountNumber (optional) - use IBAN_NORMALIZE
        - ○ merchant (optional) - use MERCHANT_EXTRACT
        - ○ merchantConfidence (optional) - use MERCHANT_CONFIDENCE

        11. MERCHANT EXTRACTION (for bank intermediary transactions):
            - When "name" contains bank intermediary, extract real merchant from description
            - Example: "ROZLICZENIE TRANSAKCJI ZAGRANICZNYCH Nadawca: Netflix" → merchant: "NETFLIX"

        ## ERROR FORMAT (if cannot parse):
        {
          "error": true,
          "errorCode": "UNRECOGNIZED_FORMAT",
          "errorMessage": "Could not determine column mapping",
          "detectedHeaders": "first line content"
        }

        ## OUTPUT:
        Return ONLY the JSON object. No other text, no markdown formatting.
        """;

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Builds user prompt with optional pre-detected delimiter.
     *
     * @param csvSample The anonymized CSV sample
     * @param bankHint Optional hint about the bank name
     * @param detectedDelimiter Pre-detected delimiter (may be null)
     * @return Complete user prompt for AI
     */
    public String buildUserPrompt(String csvSample, String bankHint, CsvFormatDetector.DetectedDelimiter detectedDelimiter) {
        String delimiterHint;
        if (detectedDelimiter != null && detectedDelimiter.confidence() > 0.5) {
            delimiterHint = String.format(
                "Pre-detected delimiter: \"%s\" (confidence: %.0f%%). USE THIS VALUE.",
                detectedDelimiter.delimiter(),
                detectedDelimiter.confidence() * 100
            );
        } else {
            delimiterHint = "No delimiter pre-detected. Analyze the CSV to determine the delimiter.";
        }

        String prompt = String.format(USER_PROMPT_TEMPLATE,
            delimiterHint,
            csvSample
        );

        if (bankHint != null && !bankHint.isBlank()) {
            prompt = prompt + "\n\n## HINT: The bank is likely: " + bankHint;
        }

        return prompt;
    }

    /**
     * Backwards-compatible method without detected format.
     */
    public String buildUserPrompt(String csvSample, String bankHint) {
        return buildUserPrompt(csvSample, bankHint, null);
    }
}
