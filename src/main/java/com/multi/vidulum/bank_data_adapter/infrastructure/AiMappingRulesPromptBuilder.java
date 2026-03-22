package com.multi.vidulum.bank_data_adapter.infrastructure;

import org.springframework.stereotype.Component;

/**
 * Builds prompts for Claude AI to generate mapping rules from bank CSV samples.
 *
 * Instead of transforming the entire CSV, AI analyzes a small sample and returns
 * reusable mapping rules in JSON format. This approach:
 * - Reduces cost (only process sample, not full file)
 * - Improves privacy (sample is anonymized before sending)
 * - Enables caching (same rules for same bank format)
 */
@Component
public class AiMappingRulesPromptBuilder {

    private static final String SYSTEM_PROMPT = """
        You are a bank CSV format analyzer. Analyze the CSV sample and return mapping rules as JSON.
        Your task is to understand the structure and return rules for LOCAL transformation.
        Return ONLY valid JSON - no markdown, no code blocks, no explanations.
        """;

    private static final String USER_PROMPT_TEMPLATE = """
        Analyze this bank CSV sample and return mapping rules to transform it to BankCsvRow format.

        ## INPUT CSV SAMPLE (first rows, may be anonymized):
        %s

        ## TARGET FORMAT (BankCsvRow):
        bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber

        ## RETURN JSON with this structure:
        {
          "bankName": "detected bank name",
          "bankCountry": "PL",
          "language": "pl",
          "dateFormat": "dd-MM-yyyy",
          "delimiter": ",",
          "encoding": "UTF-8",
          "headerRowIndex": 6,
          "metadataRows": 6,
          "originalHeader": "Data księgowania,Data operacji,...",
          "columnMappings": [
            {
              "sourceColumn": "Data operacji",
              "sourceIndex": 1,
              "targetField": "operationDate",
              "transformationType": "DATE_PARSE",
              "transformationParams": {"format": "dd-MM-yyyy"},
              "required": true
            },
            {
              "sourceColumn": "Kwota",
              "sourceIndex": 3,
              "targetField": "amount",
              "transformationType": "AMOUNT_PARSE",
              "transformationParams": {},
              "required": true
            },
            {
              "sourceColumn": "Rodzaj operacji",
              "sourceIndex": 2,
              "targetField": "bankCategory",
              "transformationType": "DIRECT",
              "transformationParams": {},
              "required": false
            },
            {
              "sourceColumn": "Kwota",
              "sourceIndex": 3,
              "targetField": "type",
              "transformationType": "TYPE_DETECT",
              "transformationParams": {"amountColumn": "3"},
              "required": true
            },
            {
              "sourceColumn": "Waluta",
              "sourceIndex": 4,
              "targetField": "currency",
              "transformationType": "CURRENCY_EXTRACT",
              "transformationParams": {"default": "PLN"},
              "required": true
            }
          ],
          "confidenceScore": 0.95,
          "warnings": [],
          "sampleInputRow": "31-12-2025,31-12-2025,Opłaty i prowizje,-10,PLN,,,Prowizja...,76047.25",
          "sampleOutputRow": "NEST_2025-12-31_001,Prowizja...,,...,10,PLN,OUTFLOW,2025-12-31,2025-12-31,,"
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
           - If no currency column → use CURRENCY_EXTRACT with default based on country (PLN for Poland, EUR for Eurozone, etc.)

        ## DETECTING THE FORMAT:
        - Look for metadata lines before header (account number, date range, totals)
        - Header row usually contains: Date, Amount, Description equivalents
        - Polish banks: "Data", "Kwota", "Tytuł operacji", "Kontrahent"
        - German banks: "Datum", "Betrag", "Verwendungszweck"
        - English banks: "Date", "Amount", "Description"

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

    public String buildUserPrompt(String csvSample, String bankHint) {
        String prompt = String.format(USER_PROMPT_TEMPLATE, csvSample);

        if (bankHint != null && !bankHint.isBlank()) {
            prompt = prompt + "\n\n## HINT: The bank is likely: " + bankHint;
        }

        return prompt;
    }
}
