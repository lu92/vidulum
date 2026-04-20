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
 * Builds prompts for transaction enrichment.
 *
 * Enrichment extracts:
 * 1. MERCHANT - normalized business/person name (always)
 * 2. BANK_CATEGORY - only if original is empty (e.g., Nest Bank)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnrichmentPromptBuilder {

    private final ObjectMapper objectMapper;

    /**
     * System prompt for enrichment.
     * Contains rules for merchant extraction and category inference.
     */
    public String getSystemPrompt() {
        return """
            You are a transaction data enrichment specialist for Polish bank statements.

            ## YOUR TASK

            For each transaction in the input, extract:
            1. **merchant** - normalized business/person name (WHO the transaction is with)
            2. **bankCategory** - ONLY if the original is empty, otherwise keep original

            ## MERCHANT EXTRACTION RULES

            Extract the clean, human-recognizable entity name:

            | Input name/description | Output merchant |
            |------------------------|-----------------|
            | "ŻABKA POLSKA 4521 WARSZAWA UL MARSZALKOWSKA" | "ŻABKA" |
            | "BIEDRONKA SKLEP 1234 KRAKÓW" | "BIEDRONKA" |
            | "BANK PEKAO S.A." + desc: "Badoo help@badoo.com Dublin" | "BADOO" |
            | "Silva Silva, Warszawa" + desc: "czynsz" | "SILVA SILVA" |
            | "NETFLIX.COM 866-579-7172" | "NETFLIX" |
            | "Przelew od Jan Kowalski" | "JAN KOWALSKI" |
            | "ZUS" | "ZUS" |
            | "SHIVAGO SPOLKA Z OGRANICZONA ODPOWIEDZIALNOSCIA" | "SHIVAGO" |
            | "ALLEGRO.PL SP. Z O.O." | "ALLEGRO" |
            | "ORLEN STACJA 1234 WARSZAWA" | "ORLEN" |
            | "MINDBOX SP Z O O WARSZAWA" | "MINDBOX" |

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
            | prowizja, opłata, fee, bank | "Opłaty bankowe" |
            | przelew przychodzący, wpływ, wynagrodzenie | "Przelewy przychodzące" |
            | przelew wychodzący, przelew do | "Przelewy wychodzące" |
            | BLIK | "Płatności BLIK" |
            | karta, card, płatność kartą | "Płatności kartą" |
            | paliwo, Orlen, BP, Shell, stacja | "Transport" |
            | restauracja, kawiarnia, bar, jedzenie | "Gastronomia" |
            | apteka, lekarz, szpital, zdrowie | "Zdrowie" |
            | ubezpieczenie, PZU, polisa | "Ubezpieczenia" |
            | telefon, internet, abonament | "Telekomunikacja" |

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
                  "merchant": "EXTRACTED_NAME",
                  "merchantConfidence": 0.95,
                  "bankCategory": "Category",
                  "bankCategorySource": "ORIGINAL" or "AI_INFERRED" or "AI_FALLBACK"
                }
              ],
              "processingNotes": "optional notes about ambiguous cases"
            }

            ## ERROR HANDLING

            If you cannot determine merchant, use fallback:
            - merchant: first recognizable word from name, uppercase
            - merchantConfidence: 0.3
            - Add note to processingNotes

            If you cannot determine bankCategory (and original is empty):
            - bankCategory: "Inne"
            - bankCategorySource: "AI_FALLBACK"

            NEVER return null or undefined values. Always return valid JSON.
            ALWAYS include all transactions from input in output (same count).
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

            return "Analyze these " + transactions.size() + " transactions and enrich each one:\n\n" + json;

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
