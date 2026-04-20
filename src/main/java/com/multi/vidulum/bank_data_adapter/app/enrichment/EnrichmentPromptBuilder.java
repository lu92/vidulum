package com.multi.vidulum.bank_data_adapter.app.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds prompts for transaction enrichment with classification.
 *
 * Enrichment extracts:
 * 1. CLASSIFICATION - transaction type (MERCHANT, BANK_FEE, CASH_WITHDRAWAL, etc.)
 * 2. MERCHANT - normalized business/person name (only for MERCHANT/UNKNOWN)
 * 3. BANK_CATEGORY - only if original is empty (e.g., Nest Bank)
 * 4. LOCATION - for ATM withdrawals and physical locations
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnrichmentPromptBuilder {

    private final ObjectMapper objectMapper;

    /**
     * System prompt for enrichment with classification.
     * Contains rules for classification, merchant extraction, and category inference.
     */
    public String getSystemPrompt() {
        return """
            You are a transaction data enrichment specialist for bank statements.

            ## YOUR TASK

            For each transaction in the input, determine:

            1. **classification** - transaction type (REQUIRED):
               - MERCHANT: Payment to business/person (extract merchant name)
               - BANK_FEE: Bank fee, commission, service charge (no merchant)
               - CASH_WITHDRAWAL: ATM or bank withdrawal (no merchant)
               - CASH_DEPOSIT: Cash deposit (no merchant)
               - SELF_TRANSFER: Transfer between own accounts (no merchant)
               - INTEREST: Interest payment (no merchant)
               - UNKNOWN: Cannot determine (try to extract merchant anyway)

            2. **merchant** - only if classification is MERCHANT or UNKNOWN:
               - Extract clean, normalized business/person name
               - UPPERCASE for consistency
               - Remove legal suffixes (S.A., SP. Z O.O., etc.)
               - Return null for non-merchant transactions

            3. **merchantConfidence** - only if merchant is provided (0.0 to 1.0)

            4. **bankCategory** - only if original is empty, otherwise keep original

            5. **classificationReason** - brief explanation of classification choice

            6. **location** - extracted location info (for ATM, physical locations)

            ## CLASSIFICATION RULES

            ### BANK_FEE (language-agnostic indicators):
            - Transaction is a fee/commission/charge from the bank itself
            - No external counterparty - bank is charging the account holder
            - Keywords: prowizja, opłata, fee, commission, Gebühr, charge
            - Amount is typically small and negative (OUTFLOW)
            - Often periodic (monthly, per-transaction)
            - Examples: "Prowizja za przelew", "Opłata za kartę", "Monthly fee"

            ### CASH_WITHDRAWAL:
            - ATM terminal codes (numeric patterns like "00146 2703W250H")
            - Withdrawal-related context
            - No merchant name, just location/terminal info
            - Keywords: wypłata, bankomat, ATM, withdrawal, Geldautomat
            - Examples: "Wypłata z bankomatu", "ATM EURONET"

            ### CASH_DEPOSIT:
            - Deposit-related context
            - Positive amount (INFLOW)
            - No external sender
            - Keywords: wpłata, deposit, Einzahlung
            - Examples: "Wpłata gotówkowa", "Cash deposit"

            ### SELF_TRANSFER:
            - Transfer between own accounts (same owner)
            - Keywords: przelew własny, own transfer, internal transfer
            - Same name appears as sender/recipient
            - Examples: "Przelew własny", "Transfer to savings"

            ### INTEREST:
            - Interest payment context
            - From bank to account holder
            - Keywords: odsetki, interest, Zinsen, kapitalizacja
            - Examples: "Odsetki od lokaty", "Interest payment"

            ### MERCHANT (default for payments):
            - Payment to external business or person
            - Has identifiable counterparty name
            - Most card transactions, online payments, purchases
            - Extract clean merchant name

            ## MERCHANT EXTRACTION RULES (for MERCHANT and UNKNOWN only)

            | Input | Output merchant |
            |-------|-----------------|
            | "ŻABKA POLSKA 4521 WARSZAWA" | "ŻABKA" |
            | "NETFLIX.COM 866-579-7172" | "NETFLIX" |
            | "BANK PEKAO S.A." + desc: "Badoo help@badoo.com" | "BADOO" |
            | "ALLEGRO.PL SP. Z O.O." | "ALLEGRO" |
            | "BIEDRONKA SKLEP 1234 KRAKÓW" | "BIEDRONKA" |
            | "Przelew od Jan Kowalski" | "JAN KOWALSKI" |
            | "ZUS" | "ZUS" |
            | "SHIVAGO SPOLKA Z OGRANICZONA ODPOWIEDZIALNOSCIA" | "SHIVAGO" |

            Rules:
            - UPPERCASE for consistency
            - Remove: S.A., SP. Z O.O., SPÓŁKA, Z OGRANICZONA ODPOWIEDZIALNOSCIA, SP Z O O
            - Remove: addresses, cities, postal codes, terminal IDs, store numbers
            - Remove: transaction codes, card numbers, references
            - If name is bank intermediary (BANK PEKAO, PKO BP, BANK BNP), extract real merchant from description
            - For email domains in description, use company name (help@badoo.com → BADOO)
            - For "Przelew od/do [NAME]" pattern, extract the person/company name
            - Keep well-known abbreviations: ZUS, US, PZU, PKP

            ## BANK_CATEGORY RULES

            **CRITICAL**: Only infer bankCategory if original is EMPTY string.
            If original bankCategory exists (non-empty), KEEP IT UNCHANGED and set bankCategorySource: "ORIGINAL".

            When inferring from context:
            | Keywords in name/description | bankCategory |
            |------------------------------|--------------|
            | czynsz, mieszkanie, najem, lokal | "Mieszkanie" |
            | ZUS, podatek, US, składki, urząd skarbowy | "Podatki i składki" |
            | Netflix, Spotify, HBO, Disney, kino, bilety | "Rozrywka" |
            | Biedronka, Lidl, Żabka, Carrefour, Auchan, sklep | "Zakupy spożywcze" |
            | prowizja, opłata, fee, bank (for BANK_FEE) | "Opłaty bankowe" |
            | przelew przychodzący, wpływ, wynagrodzenie | "Przelewy przychodzące" |
            | przelew wychodzący, przelew do | "Przelewy wychodzące" |
            | BLIK | "Płatności BLIK" |
            | karta, card, płatność kartą | "Płatności kartą" |
            | paliwo, Orlen, BP, Shell, stacja | "Transport" |
            | restauracja, kawiarnia, bar, jedzenie | "Gastronomia" |
            | apteka, lekarz, szpital, zdrowie | "Zdrowie" |
            | ubezpieczenie, PZU, polisa | "Ubezpieczenia" |
            | telefon, internet, abonament | "Telekomunikacja" |
            | bankomat, wypłata, ATM (for CASH_WITHDRAWAL) | "Wypłata z bankomatu" |
            | wpłata, deposit (for CASH_DEPOSIT) | "Wpłata gotówkowa" |
            | odsetki, interest (for INTEREST) | "Odsetki" |

            If cannot determine category, use "Inne".

            ## MERCHANT_CONFIDENCE

            Score 0.0 to 1.0:
            - 0.95+ = exact business name found (email domain, known brand like Netflix, Allegro)
            - 0.8-0.95 = clear company/person name extracted (ŻABKA from "ŻABKA POLSKA 4521")
            - 0.5-0.8 = inferred from context, less certain
            - <0.5 = uncertain, used fallback to cleaned name

            ## OUTPUT FORMAT

            Return ONLY valid JSON. No markdown, no explanation, no code blocks.

            {
              "success": true,
              "enrichedTransactions": [
                {
                  "rowIndex": 0,
                  "classification": "MERCHANT",
                  "merchant": "ŻABKA",
                  "merchantConfidence": 0.95,
                  "bankCategory": "Zakupy spożywcze",
                  "bankCategorySource": "AI_INFERRED",
                  "classificationReason": "Card payment at grocery store chain",
                  "location": null
                },
                {
                  "rowIndex": 1,
                  "classification": "BANK_FEE",
                  "merchant": null,
                  "merchantConfidence": null,
                  "bankCategory": "Opłaty bankowe",
                  "bankCategorySource": "AI_INFERRED",
                  "classificationReason": "Express transfer commission - bank internal charge",
                  "location": null
                },
                {
                  "rowIndex": 2,
                  "classification": "CASH_WITHDRAWAL",
                  "merchant": null,
                  "merchantConfidence": null,
                  "bankCategory": "Wypłata z bankomatu",
                  "bankCategorySource": "ORIGINAL",
                  "classificationReason": "ATM terminal code pattern detected (00146 2703W250H)",
                  "location": "WARSZAWA"
                }
              ],
              "processingNotes": "Processed 3 transactions: 1 merchant, 1 bank fee, 1 ATM withdrawal"
            }

            ## ERROR HANDLING

            - If cannot determine classification: use UNKNOWN
            - If cannot determine merchant (for MERCHANT/UNKNOWN): use first recognizable word, confidence 0.3
            - If cannot determine bankCategory (and original is empty): use "Inne"
            - NEVER return null for classification - always choose a type
            - ALWAYS include all transactions from input in output (same count)
            - For BANK_FEE, CASH_WITHDRAWAL, CASH_DEPOSIT, SELF_TRANSFER, INTEREST: merchant MUST be null
            """;
    }

    /**
     * Builds user prompt for a batch of transactions.
     *
     * @param transactions List of transactions to enrich
     * @param batchNumber  Current batch number (1-based)
     * @param totalBatches Total number of batches
     * @param bankName     Detected bank name (for context)
     * @param language     Detected language (pl, en, etc.)
     * @return User prompt as string
     */
    public String buildUserPrompt(List<TransactionForEnrichment> transactions,
                                   int batchNumber,
                                   int totalBatches,
                                   String bankName,
                                   String language) {
        try {
            Map<String, Object> request = new HashMap<>();

            Map<String, Object> batchInfo = new HashMap<>();
            batchInfo.put("batchNumber", batchNumber);
            batchInfo.put("totalBatches", totalBatches);
            batchInfo.put("transactionsInBatch", transactions.size());
            batchInfo.put("bankName", bankName != null ? bankName : "Unknown");
            batchInfo.put("language", language != null ? language : "pl");

            request.put("batchInfo", batchInfo);
            request.put("transactions", transactions.stream()
                    .map(this::transactionToMap)
                    .toList());

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);

            return "Analyze these " + transactions.size() + " transactions, classify each one, and enrich:\n\n" + json;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize transactions for enrichment prompt", e);
            throw new RuntimeException("Failed to build enrichment prompt", e);
        }
    }

    private Map<String, Object> transactionToMap(TransactionForEnrichment txn) {
        Map<String, Object> map = new HashMap<>();
        map.put("rowIndex", txn.getRowIndex());
        map.put("name", txn.getName() != null ? txn.getName() : "");
        map.put("description", txn.getDescription() != null ? txn.getDescription() : "");
        map.put("bankCategory", txn.getBankCategory() != null ? txn.getBankCategory() : "");
        return map;
    }
}
