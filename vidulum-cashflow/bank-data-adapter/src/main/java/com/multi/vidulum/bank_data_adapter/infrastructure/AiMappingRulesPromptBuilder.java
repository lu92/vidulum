package com.multi.vidulum.bank_data_adapter.infrastructure;

import org.springframework.stereotype.Component;

/**
 * Builds prompts for AI to generate mapping rules from bank CSV samples.
 *
 * Uses SCHEMA-FIRST approach: AI analyzes DATA CONTENT in columns (not column names)
 * to find the best match for each BankCsvRow field.
 *
 * This approach is more universal and works for any bank without hardcoding column names.
 */
@Component
public class AiMappingRulesPromptBuilder {

    private static final String SYSTEM_PROMPT = """
        You are a bank CSV format analyzer using SCHEMA-FIRST approach.

        Your task: Analyze the DATA CONTENT in each column (not just column names) to determine
        what BankCsvRow field it should map to.

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
        bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber,merchant,merchantConfidence,paymentMethod

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

        ═══════════════════════════════════════════════════════════════════════════════
        ██  SCHEMA-FIRST APPROACH - ANALYZE DATA CONTENT, NOT COLUMN NAMES!  ██
        ═══════════════════════════════════════════════════════════════════════════════

        For each BankCsvRow field below, analyze the ACTUAL DATA VALUES in each column
        to find the best match. Do NOT rely only on column names - look at what the data
        looks like!

        ┌─────────────────────────────────────────────────────────────────────────────┐
        │  operationDate (REQUIRED)                                                   │
        │  Find column containing DATE values                                         │
        │                                                                             │
        │  DATA PATTERNS to look for:                                                 │
        │  - "31-12-2025", "2025-12-31", "31.12.2025", "12/31/2025"                   │
        │  - Values that look like dates with day, month, year                        │
        │                                                                             │
        │  Use: DATE_PARSE transformation                                             │
        └─────────────────────────────────────────────────────────────────────────────┘

        ┌─────────────────────────────────────────────────────────────────────────────┐
        │  amount (REQUIRED)                                                          │
        │  Find column containing MONETARY VALUES                                     │
        │                                                                             │
        │  DATA PATTERNS to look for:                                                 │
        │  - "-3000", "1234.56", "-1 234,56", "5000 PLN"                              │
        │  - Numeric values, possibly with minus sign, decimal separator              │
        │  - May have spaces as thousands separators                                  │
        │                                                                             │
        │  Use: AMOUNT_PARSE transformation                                           │
        └─────────────────────────────────────────────────────────────────────────────┘

        ┌─────────────────────────────────────────────────────────────────────────────┐
        │  name (REQUIRED) - WHO                                                      │
        │  Find column containing COUNTERPARTY NAMES                                  │
        │                                                                             │
        │  DATA PATTERNS to look for:                                                 │
        │  - "Jan Kowalski", "Netflix Inc", "ZUS", "Urząd Skarbowy"                  │
        │  - Names of PEOPLE, COMPANIES, INSTITUTIONS                                 │
        │  - Proper nouns, organization names                                         │
        │                                                                             │
        │  ⚠️ This is WHO you paid / WHO paid you                                    │
        │  ⚠️ NOT transaction descriptions or purposes!                              │
        │                                                                             │
        │  Use: DIRECT transformation                                                 │
        └─────────────────────────────────────────────────────────────────────────────┘

        ┌─────────────────────────────────────────────────────────────────────────────┐
        │  description (REQUIRED if column exists) - WHY/WHAT                         │
        │  Find column containing TRANSACTION PURPOSE / TITLE                         │
        │                                                                             │
        │  DATA PATTERNS to look for:                                                 │
        │  - "czynsz za styczeń", "faktura 123", "składki ZUS", "rata kredytu"       │
        │  - "Invoice #12345", "Monthly rent", "Subscription fee"                     │
        │  - Reasons, references, invoice numbers, payment purposes                   │
        │                                                                             │
        │  ⚠️ This is WHY/WHAT the payment is for                                    │
        │  ⚠️ CRITICAL for AI categorization - ALWAYS map if exists!                 │
        │                                                                             │
        │  Use: DIRECT transformation                                                 │
        └─────────────────────────────────────────────────────────────────────────────┘

        ┌─────────────────────────────────────────────────────────────────────────────┐
        │  ⚠️⚠️⚠️  CRITICAL: bankCategory vs paymentMethod DISTINCTION  ⚠️⚠️⚠️       │
        │                                                                             │
        │  Banks often have TWO different classification columns:                     │
        │                                                                             │
        │  1) bankCategory = WHAT was purchased (SEMANTIC CATEGORY)                   │
        │     DATA PATTERNS to look for:                                              │
        │     - "Zakupy", "Rozrywka", "Sport", "Jedzenie", "Transport", "Medyczna"   │
        │     - "Shopping", "Entertainment", "Food", "Health", "Travel"               │
        │     - Single-word or short category labels describing spending type         │
        │                                                                             │
        │     Use: DIRECT transformation                                              │
        │                                                                             │
        │  2) paymentMethod = HOW the payment was made (TECHNICAL METHOD)             │
        │     DATA PATTERNS to look for:                                              │
        │     - "Płatność kartą", "Przelew wychodzący", "BLIK", "Zlecenie stałe"     │
        │     - "Polecenie zapłaty", "Wypłata gotówkowa", "Wpłata gotówkowa"         │
        │     - "Card payment", "Transfer", "Direct debit", "Standing order"          │
        │     - Phrases describing payment mechanism                                  │
        │                                                                             │
        │     Use: PAYMENT_METHOD_NORMALIZE transformation                            │
        │                                                                             │
        │  ════════════════════════════════════════════════════════════════════════  │
        │  EXAMPLE - Pekao Bank has BOTH columns:                                     │
        │                                                                             │
        │  Column "Typ operacji": "Płatność kartą"  → paymentMethod (HOW)            │
        │  Column "Kategoria": "Sport"               → bankCategory (WHAT)            │
        │                                                                             │
        │  WRONG: mapping "Płatność kartą" to bankCategory                           │
        │  RIGHT: mapping "Sport" to bankCategory, "Płatność kartą" to paymentMethod │
        │  ════════════════════════════════════════════════════════════════════════  │
        │                                                                             │
        │  HOW TO DISTINGUISH by looking at DATA:                                     │
        │  - If value is like "Zakupy", "Sport", "Jedzenie" → bankCategory           │
        │  - If value is like "Przelew", "Płatność kartą" → paymentMethod            │
        └─────────────────────────────────────────────────────────────────────────────┘

        ┌─────────────────────────────────────────────────────────────────────────────┐
        │  type (REQUIRED)                                                            │
        │  Determine INFLOW or OUTFLOW based on amount SIGN                          │
        │                                                                             │
        │  ALWAYS use TYPE_DETECT with amountColumn parameter!                        │
        │  - Negative amount (-100) = OUTFLOW                                         │
        │  - Positive amount (100) = INFLOW                                           │
        │                                                                             │
        │  ⚠️ NEVER map Polish transaction types to this field!                      │
        │  ⚠️ NEVER use DIRECT transformation for type!                              │
        │                                                                             │
        │  Example mapping:                                                           │
        │  {                                                                          │
        │    "sourceColumn": "Kwota",                                                 │
        │    "sourceIndex": 3,                                                        │
        │    "targetField": "type",                                                   │
        │    "transformationType": "TYPE_DETECT",                                     │
        │    "transformationParams": {"amountColumn": "3"}                            │
        │  }                                                                          │
        └─────────────────────────────────────────────────────────────────────────────┘

        ┌─────────────────────────────────────────────────────────────────────────────┐
        │  currency (REQUIRED)                                                        │
        │  Find column or extract from amount                                         │
        │                                                                             │
        │  DATA PATTERNS to look for:                                                 │
        │  - "PLN", "EUR", "USD", "GBP"                                              │
        │  - Or embedded in amount: "5000 PLN"                                        │
        │                                                                             │
        │  Use: CURRENCY_EXTRACT transformation                                       │
        │  If no currency column, set default based on country (PL → PLN)            │
        └─────────────────────────────────────────────────────────────────────────────┘

        ┌─────────────────────────────────────────────────────────────────────────────┐
        │  sourceAccountNumber / targetAccountNumber (if columns exist)              │
        │  Find columns with ACCOUNT NUMBERS (IBAN)                                  │
        │                                                                             │
        │  DATA PATTERNS to look for:                                                 │
        │  - "PL61109010140000071219812874" (full IBAN)                              │
        │  - "61109010140000071219812874" (26 digits without prefix)                 │
        │  - "'61109010140000071219812874" (with Excel apostrophe)                   │
        │                                                                             │
        │  - sourceAccountNumber = account that SENT money (for INFLOW)              │
        │  - targetAccountNumber = account that RECEIVED money (for OUTFLOW)         │
        │                                                                             │
        │  If only ONE counterparty account column exists, map to BOTH fields.       │
        │                                                                             │
        │  Use: IBAN_NORMALIZE transformation                                         │
        └─────────────────────────────────────────────────────────────────────────────┘

        ┌─────────────────────────────────────────────────────────────────────────────┐
        │  merchant / merchantConfidence (for CARD transactions)                     │
        │                                                                             │
        │  When name column contains BANK INTERMEDIARY like:                          │
        │  - "BANK PEKAO S.A.", "mBank S.A.", "ING BANK ŚLĄSKI"                      │
        │  The REAL merchant is hidden in description column:                         │
        │  - "ROZLICZENIE... Badoo help@badoo.com Dublin" → merchant: "BADOO"        │
        │                                                                             │
        │  Use: MERCHANT_EXTRACT and MERCHANT_CONFIDENCE transformations              │
        │  Set params: {"nameColumn": "X", "descriptionColumn": "Y"}                 │
        └─────────────────────────────────────────────────────────────────────────────┘

        ## TRANSFORMATION TYPES:
        - DIRECT: copy value as-is
        - DATE_PARSE: parse date using format in params
        - AMOUNT_PARSE: parse Polish/European number format (1 234,56)
        - TYPE_DETECT: determine INFLOW/OUTFLOW from amount sign
        - CURRENCY_EXTRACT: extract currency code
        - IBAN_NORMALIZE: normalize account number to full IBAN
        - CONCAT: combine multiple columns
        - REGEX_EXTRACT: extract part using regex pattern
        - VALUE_MAP: map values using lookup table
        - ID_GENERATE: generate transaction ID
        - SKIP: ignore this column
        - MERCHANT_EXTRACT: extract merchant name from description
        - MERCHANT_CONFIDENCE: calculate confidence score (0.0-1.0)
        - PAYMENT_METHOD_NORMALIZE: normalize to CARD, TRANSFER, BLIK, DIRECT_DEBIT, STANDING_ORDER, CASH, OTHER

        ## REQUIRED MAPPINGS CHECKLIST:
        ✓ operationDate (REQUIRED) - DATE_PARSE
        ✓ name (REQUIRED) - DIRECT - WHO you transacted with
        ✓ amount (REQUIRED) - AMOUNT_PARSE
        ✓ currency (REQUIRED) - CURRENCY_EXTRACT
        ✓ type (REQUIRED) - TYPE_DETECT with amountColumn param
        ✓ description (REQUIRED if exists) - DIRECT - WHY/WHAT payment is for
        ✓ bankCategory (REQUIRED if exists) - DIRECT - WHAT was purchased (semantic category)
        ✓ paymentMethod (REQUIRED if exists) - PAYMENT_METHOD_NORMALIZE - HOW payment was made
        ○ bookingDate (optional) - DATE_PARSE
        ✓ sourceAccountNumber (if exists) - IBAN_NORMALIZE
        ✓ targetAccountNumber (if exists) - IBAN_NORMALIZE
        ✓ merchant (for card transactions) - MERCHANT_EXTRACT
        ✓ merchantConfidence (when merchant mapped) - MERCHANT_CONFIDENCE

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
