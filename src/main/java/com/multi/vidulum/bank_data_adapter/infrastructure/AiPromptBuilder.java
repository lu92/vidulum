package com.multi.vidulum.bank_data_adapter.infrastructure;

import org.springframework.stereotype.Component;

/**
 * Builds prompts for Claude AI to transform bank CSV files.
 */
@Component
public class AiPromptBuilder {

    private static final String SYSTEM_PROMPT = """
        You are a bank CSV parser. Transform bank exports to BankCsvRow format.
        Return ONLY valid CSV - no markdown, no explanations, no code blocks.
        First line must be the header row.
        If you cannot parse the file, return an error in the specified format.
        """;

    private static final String USER_PROMPT_TEMPLATE = """
        Transform this bank CSV to BankCsvRow format.

        ## INPUT CSV:
        %s

        ## OUTPUT FORMAT (CSV with exact columns):
        bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber

        ## COLUMN RULES:
        - bankTransactionId: generate as {BANK}_{YYYY-MM-DD}_{row_number} (e.g., NEST_2025-12-31_001)
        - name: transaction title (REQUIRED, from "Tytuł operacji" or similar)
        - description: full description including merchant/counterparty info
        - bankCategory: original category from bank (e.g., "Przelewy wychodzące")
        - amount: ALWAYS POSITIVE decimal number (use absolute value)
        - currency: PLN, EUR, USD (3-letter code)
        - type: INFLOW (positive/credit) or OUTFLOW (negative/debit)
        - operationDate: YYYY-MM-DD format (REQUIRED)
        - bookingDate: YYYY-MM-DD format (same as operationDate if not available)
        - sourceAccountNumber: COUNTERPARTY account for INFLOW transactions (the person/company who sent money to you)
        - targetAccountNumber: COUNTERPARTY account for OUTFLOW transactions (the person/company you paid)

        CRITICAL ACCOUNT NUMBER PLACEMENT RULE (READ CAREFULLY):
        The bank CSV column "Numer rachunku kontrahenta" contains the counterparty account number.
        You MUST place it in the CORRECT column based on transaction type:

        IF type=INFLOW (money coming IN to your account):
          - sourceAccountNumber = counterparty account (who SENT money)
          - targetAccountNumber = EMPTY (leave blank)

        IF type=OUTFLOW (money going OUT of your account):
          - sourceAccountNumber = EMPTY (leave blank)
          - targetAccountNumber = counterparty account (who RECEIVED money)

        EXAMPLES:
        - INFLOW from MINDBOX (account 82109018830000000109874194):
          ...,INFLOW,2025-12-11,2025-12-11,PL82109018830000000109874194,,
          (sourceAccountNumber filled, targetAccountNumber empty)

        - OUTFLOW to ZUS (account 83101010230000261395100000):
          ...,OUTFLOW,2025-12-11,2025-12-11,,PL83101010230000261395100000,
          (sourceAccountNumber empty, targetAccountNumber filled)

        ## IBAN NORMALIZATION (CRITICAL):
        Account numbers MUST be normalized to full IBAN format with country prefix:
        - If account has 26 digits without prefix AND currency is PLN → add "PL" prefix
        - If account has 22 digits without prefix AND currency is EUR → add "DE" prefix (or detect from bank)
        - If account already has 2-letter country prefix (PL, DE, GB, etc.) → keep as is
        - Remove all spaces and dashes from account numbers
        - Examples:
          - "98124014441111001078171074" (26 digits, PLN) → "PL98124014441111001078171074"
          - "PL98124014441111001078171074" → "PL98124014441111001078171074" (already correct)
          - "22102049002879287900000091" (26 digits, PLN) → "PL22102049002879287900000091"
        - IBAN lengths per country: PL=28, DE=22, GB=22, FR=27, ES=24, IT=27, NL=18

        ## TRANSFORMATION RULES:
        1. SKIP metadata header lines (account info, date ranges, totals, summaries)
        2. Find actual column headers row (contains "Data", "Kwota", etc.)
        3. Convert dates: DD-MM-YYYY or DD.MM.YYYY → YYYY-MM-DD
        4. Replace "|" characters with spaces (used as line breaks in Polish banks)
        5. Negative amount = OUTFLOW, positive = INFLOW
        6. Escape fields containing commas with double quotes
        7. Handle Polish characters (ąćęłńóśźż) correctly

        ## LANGUAGE HANDLING:
        - Input CSV may be in ANY language (Polish, German, English, French, etc.)
        - Detect the language and country automatically
        - Column names vary by language - use semantic understanding:
          - Date columns: "Data", "Datum", "Date", "Fecha", "Data operacji"
          - Amount columns: "Kwota", "Betrag", "Amount", "Montant", "Importo"
          - Description columns: "Opis", "Verwendungszweck", "Description", "Tytuł"
        - Output CSV is ALWAYS in English format (BankCsvRow columns)
        - Keep original transaction descriptions in their original language
        - Detect country from: bank name, IBAN format, currency, language

        ## METADATA TO INCLUDE (as comment at end, after blank line):
        After the CSV data, add a blank line and then these metadata lines:
        # DETECTED_BANK: {bank name}
        # DETECTED_LANGUAGE: {language code like pl, de, en}
        # DETECTED_COUNTRY: {country code like PL, DE, UK}
        # ROW_COUNT: {number of data rows}
        # WARNINGS: {any warnings, comma separated, or "none"}

        ## ERROR FORMAT (if cannot parse):
        If you cannot parse the file, return ONLY:
        ERROR: {error_code}
        MESSAGE: {human_readable_message}
        SAMPLE: {first 3 problematic lines}

        Error codes: UNRECOGNIZED_FORMAT, MISSING_REQUIRED_COLUMN, DATE_PARSE_ERROR, EMPTY_FILE

        ## OUTPUT:
        Return the CSV content starting with header row, followed by metadata comments. No other text.
        """;

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String buildUserPrompt(String csvContent, String bankHint) {
        String prompt = String.format(USER_PROMPT_TEMPLATE, csvContent);

        if (bankHint != null && !bankHint.isBlank()) {
            prompt = prompt + "\n\n## HINT: The bank is likely: " + bankHint;
        }

        return prompt;
    }
}
