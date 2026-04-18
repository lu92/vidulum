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

        9. CRITICAL - NAME vs DESCRIPTION DISTINCTION:

           Bank CSVs typically have TWO different text columns that must be mapped correctly:

           "name" = WHO (counterparty)
           - The person, company, or entity you transacted with
           - Answer the question: "Who did I pay?" or "Who paid me?"
           - Examples: "John Smith", "Netflix", "Tax Office", "Landlord LLC"

           "description" = WHY/WHAT (transaction purpose)
           - The reason, purpose, or reference for the payment
           - Answer the question: "What is this payment for?"
           - Examples: "Monthly rent", "Invoice #12345", "Salary January", "Loan payment"

           EXAMPLES OF CORRECT MAPPING:
           | name (WHO)              | description (WHY/WHAT)                    |
           |-------------------------|-------------------------------------------|
           | "John Smith"            | "Rent payment January 2025"               |
           | "Social Insurance"      | "Monthly contribution"                    |
           | "Netflix"               | "Subscription fee"                        |
           | "Tax Authority"         | "Income tax Q4 2024"                      |
           | "Electric Company"      | "Invoice 2025/01/1234"                    |

           HOW TO IDENTIFY EACH COLUMN:
           - name column: contains names of people, companies, institutions
           - description column: contains purposes, references, invoice numbers, payment reasons
             Look for column names with words like: title, purpose, reference, description, memo, reason

        10. DESCRIPTION FIELD (CRITICAL FOR CATEGORIZATION):

            ⚠️ The description field is ESSENTIAL for AI transaction categorization!

            WITHOUT description, the system cannot distinguish between:
            - Different types of payments to the same recipient
            - The actual purpose of generic bank transfers
            - Subcategories within the same expense type

            EXAMPLE: Two payments to "Social Insurance Office":
            - description: "retirement contribution" → Category: Retirement
            - description: "health insurance" → Category: Healthcare
            Without description, both would go to generic "Other expenses"!

            ALWAYS map the transaction purpose/title/reference column to description.
            If the CSV has such a column, you MUST include it in columnMappings.

        ## REQUIRED MAPPINGS CHECKLIST:
        You MUST include mappings for ALL of these fields:
        - ✓ operationDate (REQUIRED) - use DATE_PARSE
        - ✓ name (REQUIRED) - WHO you transacted with - use DIRECT or CONCAT
        - ✓ amount (REQUIRED) - use AMOUNT_PARSE
        - ✓ currency (REQUIRED) - use CURRENCY_EXTRACT
        - ✓ type (REQUIRED) - use TYPE_DETECT with amountColumn param
        - ✓ bankCategory (REQUIRED if exists) - use DIRECT - map transaction type/category column
        - ✓ description (REQUIRED if exists) - WHY/WHAT the payment is for - use DIRECT
        - ○ bookingDate (optional) - use DATE_PARSE
        - ✓ sourceAccountNumber (REQUIRED if exists) - use IBAN_NORMALIZE - sender account for grouping
        - ✓ targetAccountNumber (REQUIRED if exists) - use IBAN_NORMALIZE - recipient account for grouping
        - ✓ merchant (REQUIRED for card transactions) - use MERCHANT_EXTRACT
        - ✓ merchantConfidence (REQUIRED when merchant is mapped) - use MERCHANT_CONFIDENCE

        ⚠️ If the CSV has a column with transaction purpose/title/reference, you MUST map it to description!

        14. ACCOUNT NUMBER MAPPING (IMPORTANT FOR TRANSACTION GROUPING):

            Account numbers help identify unique counterparties for pattern matching.
            If CSV has columns with account numbers, you MUST map them:

            - sourceAccountNumber: Account that SENT money (for INFLOW transactions)
              Common column names: "Rachunek źródłowy", "Source Account", "Sender Account", "From Account"

            - targetAccountNumber: Account that RECEIVED money (for OUTFLOW transactions)
              Common column names: "Rachunek docelowy", "Target Account", "Beneficiary Account", "To Account"

            Note: Some banks use a single "counterparty account" column for both.
            In that case, map it to BOTH sourceAccountNumber and targetAccountNumber -
            the system will use the correct one based on transaction type.

            IMPORTANT: Account numbers may have leading apostrophe (') in CSV - this is Excel formatting.
            Use IBAN_NORMALIZE transformation to clean and normalize account numbers.

        15. BANK CATEGORY MAPPING (IMPORTANT):
            - bankCategory is the bank's transaction type/category
            - Common source columns: "Rodzaj operacji", "Typ operacji", "Kategoria", "Transaction Type", "Category"
            - This is different from type (INFLOW/OUTFLOW) - it's the bank's classification
            - Examples of bank categories: "Przelewy wychodzące", "Opłaty i prowizje", "Płatności kartą"
            - If such column exists, you MUST map it to bankCategory

        13. MERCHANT EXTRACTION (CRITICAL FOR CARD TRANSACTIONS):

            ⚠️ THIS IS EXTREMELY IMPORTANT FOR ACCURATE CATEGORIZATION!

            Bank card transactions often show the BANK as the counterparty (name column),
            while the REAL MERCHANT is hidden in the description/title column.

            EXAMPLES OF BANK INTERMEDIARY PATTERNS (in name column):
            - "BANK PEKAO S.A." → Card transaction processed by Pekao bank
            - "BANK MILLENNIUM S.A." → Card transaction processed by Millennium
            - "BNP PARIBAS BANK POLSKA" → Card transaction processed by BNP
            - "SANTANDER BANK POLSKA" → Card transaction processed by Santander
            - "ING BANK ŚLĄSKI" → Card transaction processed by ING
            - "mBank S.A." → Card transaction processed by mBank
            - Any pattern containing "ROZLICZENIE TRANSAKCJI" or "TRANSAKCJA KARTĄ"

            WHEN YOU SEE BANK INTERMEDIARY IN NAME COLUMN, YOU MUST:
            1. Add merchant mapping with MERCHANT_EXTRACT transformation
            2. Add merchantConfidence mapping with MERCHANT_CONFIDENCE transformation
            3. Set nameColumn parameter pointing to the name column index

            MERCHANT EXTRACTION EXAMPLE:
            If CSV has:
            - Column 2: "Nadawca / Odbiorca" = "BANK PEKAO S.A."
            - Column 6: "Tytułem" = "ROZLICZENIE... Badoo help@badoo.com Dublin"

            You MUST add these mappings:
            {
              "sourceColumn": "Tytułem",
              "sourceIndex": 6,
              "targetField": "merchant",
              "transformationType": "MERCHANT_EXTRACT",
              "transformationParams": {"nameColumn": "2", "descriptionColumn": "6"},
              "required": false
            },
            {
              "sourceColumn": "Tytułem",
              "sourceIndex": 6,
              "targetField": "merchantConfidence",
              "transformationType": "MERCHANT_CONFIDENCE",
              "transformationParams": {"nameColumn": "2", "descriptionColumn": "6"},
              "required": false
            }

            The MERCHANT_EXTRACT transformer will:
            - Check if name column contains bank intermediary
            - Extract real merchant from description (e.g., "BADOO", "NETFLIX", "UBER")
            - Return empty if name is already the real merchant

            WITHOUT MERCHANT EXTRACTION:
            - All card transactions will be grouped under "BANK PEKAO S.A." pattern
            - System will categorize them ALL as "Other expenses"
            - User loses visibility into BADOO, NETFLIX, UBER, etc. spending

            WITH MERCHANT EXTRACTION:
            - Each merchant (BADOO, NETFLIX, UBER) becomes a separate pattern
            - System can categorize BADOO → Dating, NETFLIX → Streaming, UBER → Transport
            - User gets accurate spending breakdown by actual merchant

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
