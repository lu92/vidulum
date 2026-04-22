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
               - MERCHANT: Payment to business/person/government (extract merchant name)
               - BANK_FEE: Bank fee, commission, service charge FROM THE BANK ITSELF (no merchant)
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

            ## CLASSIFICATION RULES (CHECK IN THIS ORDER!)

            ### 1. SELF_TRANSFER (check first!)
            - Bank category "Przelew wewnętrzny" → ALWAYS SELF_TRANSFER
            - Keywords: "przelew własny", "own account", "between accounts", "własne konto"
            - Transfer to/from same person name
            - merchant = null for all self-transfers
            - Examples: "Przelew własny", "Transfer to savings", "Przelew wewnętrzny"

            ### 2. BANK_FEE (STRICT DEFINITION - read carefully!)

            ⚠️ CRITICAL: BANK_FEE means fee charged BY THE BANK ITSELF to the account holder.

            ✅ IS BANK_FEE (bank charges YOU):
            - "Prowizja za przelew" (transfer commission from your bank)
            - "Opłata za kartę" (card fee from your bank)
            - "Opłata za konto" (account fee)
            - "Prowizja za przelew natychmiastowy" (instant transfer fee)
            - Name contains only your bank name: "Bank Pekao S.A.", "Nest Bank", "PKO BP"
            - Transaction where bank is both the source and description indicates fee

            ❌ IS NOT BANK_FEE - these are MERCHANT:
            - ZUS (Zakład Ubezpieczeń Społecznych) → MERCHANT, government entity
            - Urząd Skarbowy / US → MERCHANT, tax office
            - IKANO Bank, IKANO → MERCHANT, loan repayment to external bank
            - Credit Agricole (rata/rat.) → MERCHANT, loan repayment
            - Santander Consumer → MERCHANT, loan repayment
            - Any payment TO an external organization → MERCHANT

            RULE: If money goes TO an external entity (not YOUR bank), it's MERCHANT, not BANK_FEE.
            RULE: Loan repayments to other banks/finance companies = MERCHANT

            ### 3. CASH_WITHDRAWAL
            - ATM terminal codes (numeric patterns like "00146 2703W250H")
            - "BANKOMAT", "ATM", "Wypłata z bankomatu", "EURONET"
            - No merchant name, just location/terminal info
            - merchant = null, extract location
            - Examples: "Wypłata z bankomatu", "ATM EURONET", "BANKOMAT EURONET"

            ### 4. CASH_DEPOSIT
            - "Wpłata gotówkowa", "Cash deposit", "Wpłata własna"
            - Positive amount (INFLOW), no external sender
            - merchant = null
            - Examples: "Wpłata gotówkowa", "Cash deposit"

            ### 5. INTEREST
            - "Odsetki", "Interest", "Kapitalizacja odsetek"
            - From bank to account holder (INFLOW)
            - merchant = null
            - Examples: "Odsetki od lokaty", "Interest payment", "Kapitalizacja"

            ### 6. MERCHANT (default for most payments)
            - Payment to external business, person, or government entity
            - Has identifiable counterparty name
            - Most card transactions, online payments, purchases
            - Government payments (ZUS, taxes, fines)
            - Loan repayments to external financial institutions
            - Extract clean merchant name

            ### 7. UNKNOWN (last resort only)
            - Cannot determine type with any confidence
            - Still try to extract merchant name with low confidence

            ## GOVERNMENT ENTITIES ARE MERCHANTS (NOT BANK_FEE!)

            | Entity | merchant | bankCategory | Notes |
            |--------|----------|--------------|-------|
            | ZUS | ZUS | Podatki i składki | Social security - ALWAYS MERCHANT |
            | Urząd Skarbowy, US | URZAD SKARBOWY | Podatki i składki | Tax payments |
            | GITD, Inspektorat | GITD | Opłaty urzędowe | Traffic fines |
            | Urząd Miasta/Gminy | URZAD MIASTA | Opłaty urzędowe | City/municipal fees |
            | PIT, VAT payments | URZAD SKARBOWY | Podatki i składki | Tax payments |

            ## LOAN/FINANCE COMPANIES ARE MERCHANTS (NOT BANK_FEE!)

            | Pattern | merchant | bankCategory | Notes |
            |---------|----------|--------------|-------|
            | IKANO, IKANO BANK | IKANO | Spłata kredytu | Consumer loans |
            | Santander Consumer | SANTANDER | Spłata kredytu | Consumer finance |
            | Credit Agricole + rata/rat. | CREDIT AGRICOLE | Spłata kredytu | Loan installment |
            | Provident | PROVIDENT | Spłata kredytu | Consumer loans |
            | Alior Bank (rata) | ALIOR | Spłata kredytu | Loan repayment |

            ## KNOWN SUBSCRIPTION SERVICES (always MERCHANT, high confidence)

            | Pattern in name/description | merchant | confidence |
            |-----------------------------|----------|------------|
            | Netflix, NETFLIX.COM | NETFLIX | 0.95 |
            | Spotify, SPOTIFY | SPOTIFY | 0.95 |
            | Badoo, help@badoo.com, badoo.com | BADOO | 0.95 |
            | HBO, HBOMAX, HBO MAX | HBO MAX | 0.95 |
            | Disney+, DISNEYPLUS | DISNEY+ | 0.95 |
            | YouTube Premium | YOUTUBE | 0.95 |
            | Apple, iTunes, APPLE.COM | APPLE | 0.95 |
            | Google Play, GOOGLE | GOOGLE | 0.95 |
            | Amazon Prime, AMAZON | AMAZON | 0.95 |
            | Allegro, ALLEGRO.PL | ALLEGRO | 0.95 |
            | TradingView | TRADINGVIEW | 0.95 |

            When bank name appears (BANK PEKAO, PKO BP) but description contains
            email domain or service name → extract real merchant from description.

            Example:
              name: "BANK PEKAO S.A."
              description: "ROZLICZENIE TRANSAKCJI... Badoo help@badoo.com"
              → merchant: "BADOO", classification: MERCHANT, confidence: 0.95

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
            | "Urząd skarbowy w Mielcu" | "URZAD SKARBOWY" |
            | "IKANO" | "IKANO" |
            | "SHIVAGO SPOLKA Z OGRANICZONA ODPOWIEDZIALNOSCIA" | "SHIVAGO" |

            Rules:
            - UPPERCASE for consistency
            - Remove: S.A., SP. Z O.O., SPÓŁKA, Z OGRANICZONA ODPOWIEDZIALNOSCIA, SP Z O O
            - Remove: addresses, cities, postal codes, terminal IDs, store numbers
            - Remove: transaction codes, card numbers, references
            - If name is bank intermediary (BANK PEKAO, PKO BP, BANK BNP), extract real merchant from description
            - For email domains in description, use company name (help@badoo.com → BADOO)
            - For "Przelew od/do [NAME]" pattern, extract the person/company name
            - Keep well-known abbreviations: ZUS, US, PZU, PKP, GITD

            ## BANK_CATEGORY RULES

            **CRITICAL**: Only infer bankCategory if original is EMPTY string.
            If original bankCategory exists (non-empty), KEEP IT UNCHANGED and set bankCategorySource: "ORIGINAL".

            When inferring from context:
            | Keywords in name/description | bankCategory |
            |------------------------------|--------------|
            | czynsz, mieszkanie, najem, lokal | "Mieszkanie" |
            | ZUS, podatek, US, składki, urząd skarbowy, PIT, VAT | "Podatki i składki" |
            | IKANO, Credit Agricole rata, Santander Consumer, Provident | "Spłata kredytu" |
            | Netflix, Spotify, HBO, Disney, kino, bilety | "Rozrywka" |
            | Biedronka, Lidl, Żabka, Carrefour, Auchan, sklep | "Zakupy spożywcze" |
            | prowizja, opłata za konto/kartę (ONLY for BANK_FEE) | "Opłaty bankowe" |
            | przelew przychodzący, wpływ, wynagrodzenie | "Przelewy przychodzące" |
            | przelew wychodzący, przelew do | "Przelewy wychodzące" |
            | przelew wewnętrzny, przelew własny | "Przelew wewnętrzny" |
            | BLIK | "Płatności BLIK" |
            | karta, card, płatność kartą | "Płatności kartą" |
            | paliwo, Orlen, BP, Shell, stacja | "Transport" |
            | restauracja, kawiarnia, bar, jedzenie | "Gastronomia" |
            | apteka, lekarz, szpital, zdrowie | "Zdrowie" |
            | ubezpieczenie, PZU, polisa, Warta | "Ubezpieczenia" |
            | telefon, internet, abonament, Plus, Orange, T-Mobile | "Telekomunikacja" |
            | bankomat, wypłata, ATM (for CASH_WITHDRAWAL) | "Wypłata z bankomatu" |
            | wpłata, deposit (for CASH_DEPOSIT) | "Wpłata gotówkowa" |
            | odsetki, interest (for INTEREST) | "Odsetki" |
            | GITD, mandat, kara, inspektorat | "Opłaty urzędowe" |

            If cannot determine category, use "Inne".

            ## MERCHANT_CONFIDENCE

            Score 0.0 to 1.0:
            - 0.95+ = exact business name found (email domain, known brand like Netflix, Allegro, ZUS)
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
                  "merchant": "ZUS",
                  "merchantConfidence": 0.95,
                  "bankCategory": "Podatki i składki",
                  "bankCategorySource": "AI_INFERRED",
                  "classificationReason": "Payment to ZUS - government social security entity",
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
                  "classification": "SELF_TRANSFER",
                  "merchant": null,
                  "merchantConfidence": null,
                  "bankCategory": "Przelew wewnętrzny",
                  "bankCategorySource": "ORIGINAL",
                  "classificationReason": "Bank-categorized as internal transfer (Przelew wewnętrzny)",
                  "location": null
                },
                {
                  "rowIndex": 3,
                  "classification": "MERCHANT",
                  "merchant": "BADOO",
                  "merchantConfidence": 0.95,
                  "bankCategory": "Rozrywka",
                  "bankCategorySource": "AI_INFERRED",
                  "classificationReason": "Subscription service - extracted from email domain in description",
                  "location": "DUBLIN"
                }
              ],
              "processingNotes": "Processed 4 transactions: 2 merchants, 1 bank fee, 1 self-transfer"
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
