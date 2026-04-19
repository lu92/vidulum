package com.multi.vidulum.bank_data_ingestion.app.categorization;

import com.multi.vidulum.bank_data_ingestion.domain.PatternMapping;
import com.multi.vidulum.cashflow.domain.Type;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds prompts for AI-powered transaction categorization.
 *
 * The prompt is designed to:
 * 1. Create a nested category structure (parent/child)
 * 2. Map transaction patterns to categories
 * 3. Return structured JSON for easy parsing
 */
@Component
public class AiCategorizationPromptBuilder {

    /**
     * System prompt that defines the AI's role and output format.
     *
     * @param detectedLanguage language code (e.g., "pl", "en", "de") or null for default (Polish)
     */
    public String getSystemPrompt(String detectedLanguage) {
        String languageName = getLanguageName(detectedLanguage);
        String languageSpecificGuidelines = getLanguageSpecificGuidelines(detectedLanguage);

        return """
            You are a financial transaction categorization expert for personal finance.
            Your task is to analyze transaction patterns and suggest an optimal nested category structure.

            IMPORTANT: The detected language of transactions is %s. All category names MUST be in %s.

            Guidelines:

            1. Create hierarchical categories (parent → subcategories) ONLY when there are 2+ subcategories
            2. Use category names in the detected language (%s)
            3. Keep structure practical - 5-8 parent categories max
            4. Each subcategory should be specific enough to be useful
            5. Consider transaction frequency and amounts when prioritizing
            6. FLATTEN single-child hierarchies: if a parent would have only 1 subcategory, DON'T create the parent - use the subcategory directly as a root category
            7. Only include categories that have actual transactions mapping to them
            8. CATEGORY NAME UNIQUENESS: Names must be unique within each type (INFLOW or OUTFLOW separately). The same name CAN exist in both types if logically justified (e.g., "Inne" for both income and expenses is OK).
            9. HIERARCHY CONSISTENCY FROM CACHE: When CACHED PATTERN INTENTS section is provided, respect intendedParent as a hint for hierarchy. If current structure differs from cached intents, you MAY suggest structureOptimizations to reorganize categories.
            10. TITLE FIELD ANALYSIS - CRITICAL FOR ACCURATE CATEGORIZATION:
                The "title" field contains the PURPOSE or REASON for the transaction. This is often MORE important than the merchant name for categorization!

                ALWAYS analyze "title" to understand the transaction's real purpose:
                - "title: czynsz za styczeń 2026" → This is RENT payment, regardless of recipient
                - "title: składka ZUS" → This is SOCIAL INSURANCE, even if paid to generic account
                - "title: darowizna dla schroniska" → This is DONATION/CHARITY
                - "title: zwrot za bilet" → This is REFUND, not transportation
                - "title: opłata za przedszkole" → This is CHILDCARE/EDUCATION

                The "name" field shows WHO you transacted with.
                The "title" field shows WHY/WHAT the payment is for.

                Example: Two payments to the same person "Jan Kowalski":
                - title: "czynsz za lokal" → Category: "Wynajem" (Rent)
                - title: "pożyczka" → Category: "Pożyczki" (Loans)

                Without analyzing title, both would incorrectly go to generic "Przelewy"!

            Category types:
            - OUTFLOW: Expenses (wydatki)
            - INFLOW: Income (przychody)

            %s

            You MUST respond with valid JSON only, no markdown, no explanation.
            """.formatted(languageName, languageName, languageName, languageSpecificGuidelines);
    }

    /**
     * Returns language-specific category examples based on detected language.
     */
    private String getLanguageSpecificGuidelines(String detectedLanguage) {
        if ("pl".equalsIgnoreCase(detectedLanguage) || detectedLanguage == null || detectedLanguage.isBlank()) {
            return """
            Common Polish parent categories for OUTFLOW:
            - "Opłaty obowiązkowe" (rent, utilities, insurance, subscriptions)
            - "Żywność" (groceries, restaurants, delivery)
            - "Transport" (fuel, public transport, taxi, car maintenance)
            - "Zdrowie" (pharmacy, medical visits)
            - "Rozrywka" (entertainment, hobbies, streaming)
            - "Zakupy" (clothing, electronics, home)
            - "Oszczędności i inwestycje" (savings, investments)
            - "Inne wydatki" (other, uncategorized)

            Common Polish parent categories for INFLOW:
            - "Wynagrodzenie" (salary, bonus)
            - "Działalność" (business income)
            - "Zwroty" (refunds, returns)
            - "Odsetki" (interest, dividends)
            - "Inne przychody" (other income)
            """;
        } else if ("en".equalsIgnoreCase(detectedLanguage)) {
            return """
            Common English parent categories for OUTFLOW:
            - "Bills & Utilities" (rent, utilities, insurance, subscriptions)
            - "Food & Dining" (groceries, restaurants, delivery)
            - "Transportation" (fuel, public transport, taxi, car maintenance)
            - "Health & Medical" (pharmacy, medical visits)
            - "Entertainment" (entertainment, hobbies, streaming)
            - "Shopping" (clothing, electronics, home)
            - "Savings & Investments" (savings, investments)
            - "Other Expenses" (other, uncategorized)

            Common English parent categories for INFLOW:
            - "Salary" (salary, bonus)
            - "Business Income" (freelance, self-employment)
            - "Refunds" (refunds, returns)
            - "Interest & Dividends" (interest, dividends)
            - "Other Income" (other income)
            """;
        } else if ("de".equalsIgnoreCase(detectedLanguage)) {
            return """
            Common German parent categories for OUTFLOW:
            - "Fixkosten" (rent, utilities, insurance, subscriptions)
            - "Lebensmittel & Gastronomie" (groceries, restaurants, delivery)
            - "Transport" (fuel, public transport, taxi, car maintenance)
            - "Gesundheit" (pharmacy, medical visits)
            - "Unterhaltung" (entertainment, hobbies, streaming)
            - "Einkäufe" (clothing, electronics, home)
            - "Sparen & Investitionen" (savings, investments)
            - "Sonstige Ausgaben" (other, uncategorized)

            Common German parent categories for INFLOW:
            - "Gehalt" (salary, bonus)
            - "Geschäftseinkommen" (business income)
            - "Erstattungen" (refunds, returns)
            - "Zinsen & Dividenden" (interest, dividends)
            - "Sonstige Einnahmen" (other income)
            """;
        } else {
            // Default to English for unknown languages
            return """
            Create category names in %s language.

            Common parent categories for OUTFLOW:
            - Bills & Utilities (rent, utilities, insurance, subscriptions)
            - Food & Dining (groceries, restaurants, delivery)
            - Transportation (fuel, public transport, taxi, car maintenance)
            - Health & Medical (pharmacy, medical visits)
            - Entertainment (entertainment, hobbies, streaming)
            - Shopping (clothing, electronics, home)
            - Savings & Investments (savings, investments)
            - Other Expenses (other, uncategorized)

            Common parent categories for INFLOW:
            - Salary (salary, bonus)
            - Business Income (freelance, self-employment)
            - Refunds (refunds, returns)
            - Interest & Dividends (interest, dividends)
            - Other Income (other income)
            """.formatted(getLanguageName(detectedLanguage));
        }
    }

    /**
     * Returns human-readable language name from language code.
     */
    private String getLanguageName(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return "Polish"; // Default
        }
        return switch (languageCode.toLowerCase()) {
            case "pl" -> "Polish";
            case "en" -> "English";
            case "de" -> "German";
            case "fr" -> "French";
            case "es" -> "Spanish";
            case "it" -> "Italian";
            case "cs" -> "Czech";
            case "sk" -> "Slovak";
            default -> languageCode.toUpperCase() + " language";
        };
    }

    /**
     * Builds the user prompt with pattern groups.
     *
     * @param patternGroups the deduplicated pattern groups
     * @param categoryStructure existing categories with type and hierarchy
     * @param cachedPatternIntents patterns from cache with intendedParentCategory hints
     * @param detectedLanguage language code (e.g., "pl", "en", "de") or null for default (Polish)
     * @return the user prompt
     */
    public String buildUserPrompt(List<PatternDeduplicator.PatternGroup> patternGroups,
                                   ExistingCategoryStructure categoryStructure,
                                   List<PatternMapping> cachedPatternIntents,
                                   String detectedLanguage) {

        StringBuilder sb = new StringBuilder();

        String languageName = getLanguageName(detectedLanguage);
        sb.append("Analyze these transaction patterns and suggest categories.\n");
        sb.append("IMPORTANT: All category names MUST be in ").append(languageName).append(".\n\n");

        // Add existing categories context WITH TYPE AND HIERARCHY
        if (categoryStructure != null && !categoryStructure.isEmpty()) {
            sb.append("EXISTING CATEGORIES (PREFER these over creating new ones!):\n\n");

            // INFLOW categories with hierarchy
            if (!categoryStructure.inflowCategories().isEmpty()) {
                sb.append("  INFLOW (przychody):\n");
                formatCategoryHierarchy(categoryStructure.inflowCategories(), sb, "    ");
                sb.append("\n");
            }

            // OUTFLOW categories with hierarchy
            if (!categoryStructure.outflowCategories().isEmpty()) {
                sb.append("  OUTFLOW (wydatki):\n");
                formatCategoryHierarchy(categoryStructure.outflowCategories(), sb, "    ");
                sb.append("\n");
            }
        }

        // Add cached pattern intents (Phase 2: hierarchy hints from previous imports)
        List<PatternMapping> patternsWithIntents = cachedPatternIntents.stream()
                .filter(pm -> pm.intendedParentCategory() != null && !pm.intendedParentCategory().isBlank())
                .limit(10) // Limit to avoid prompt explosion
                .toList();

        if (!patternsWithIntents.isEmpty()) {
            sb.append("CACHED PATTERN INTENTS (hierarchy hints from previous imports):\n");
            sb.append("These patterns were previously assigned to categories with intended parent hierarchies.\n");
            sb.append("Consider these when building category structure:\n\n");

            for (PatternMapping pm : patternsWithIntents) {
                sb.append(String.format("  - Pattern: %s → Category: %s (intendedParent: %s) [%s]\n",
                        pm.normalizedPattern(),
                        pm.suggestedCategory(),
                        pm.intendedParentCategory(),
                        pm.categoryType()));
            }
            sb.append("\n");
        }

        // Group by type
        List<PatternDeduplicator.PatternGroup> outflows = patternGroups.stream()
                .filter(p -> p.type() == Type.OUTFLOW)
                .toList();
        List<PatternDeduplicator.PatternGroup> inflows = patternGroups.stream()
                .filter(p -> p.type() == Type.INFLOW)
                .toList();

        // Add outflows
        if (!outflows.isEmpty()) {
            sb.append("OUTFLOW PATTERNS (").append(outflows.size()).append(" unique):\n");
            for (PatternDeduplicator.PatternGroup pg : outflows) {
                sb.append(formatPatternGroup(pg));
            }
            sb.append("\n");
        }

        // Add inflows
        if (!inflows.isEmpty()) {
            sb.append("INFLOW PATTERNS (").append(inflows.size()).append(" unique):\n");
            for (PatternDeduplicator.PatternGroup pg : inflows) {
                sb.append(formatPatternGroup(pg));
            }
            sb.append("\n");
        }

        // Add unique bank categories that need mapping
        Set<String> uniqueBankCategories = patternGroups.stream()
                .map(PatternDeduplicator.PatternGroup::bankCategory)
                .filter(bc -> bc != null && !bc.isBlank())
                .collect(Collectors.toSet());

        if (!uniqueBankCategories.isEmpty()) {
            sb.append("""

                ⚠️ WARNING ABOUT BANK CATEGORIES:
                The following are ACTUAL bankCategory values from the bank's CSV export.
                Most Polish banks use GENERIC categories that are NOT useful for categorization:
                - "TRANSAKCJA KARTĄ PŁATNICZĄ" = card payment (covers 60%+ of all transactions!)
                - "PRZELEW" / "PRZELEW WYCHODZĄCY" = wire transfer
                - "PŁATNOŚĆ BLIK" = BLIK payment

                DO NOT create bankCategoryMappings for these generic categories!
                Instead, focus on creating patternMappings for each merchant pattern shown above.

                REMEMBER: bankCategory values are shown in "bank:" field of each pattern above.
                Only values from that field can be used in bankCategoryMappings!

                """);
            sb.append("UNIQUE BANK CATEGORIES (create mappings for those not matching existing categories):\n");
            for (String bankCategory : uniqueBankCategories) {
                Type categoryType = patternGroups.stream()
                        .filter(pg -> bankCategory.equals(pg.bankCategory()))
                        .map(PatternDeduplicator.PatternGroup::type)
                        .findFirst()
                        .orElse(Type.OUTFLOW);

                boolean directMatch = categoryStructure != null &&
                        categoryStructure.containsCategoryIgnoreCase(bankCategory);

                if (directMatch) {
                    sb.append("  - ").append(bankCategory).append(" [").append(categoryType).append("] → DIRECT MATCH (no mapping needed)\n");
                } else {
                    sb.append("  - ").append(bankCategory).append(" [").append(categoryType).append("] → NEEDS MAPPING to existing category\n");
                }
            }
            sb.append("\n");
        }

        // Expected output format with improved JSON structure
        sb.append("""

            Return JSON in this exact format:
            {
              "categoryStructure": {
                "outflow": [
                  {
                    "name": "Parent Category Name",
                    "subCategories": ["Sub1", "Sub2"]
                  }
                ],
                "inflow": [
                  {
                    "name": "Parent Category Name",
                    "subCategories": ["Sub1", "Sub2"]
                  }
                ]
              },
              "patternMappings": [
                {
                  "pattern": "BIEDRONKA",
                  "suggestedCategory": "Zakupy spożywcze",
                  "parentCategory": "Żywność",
                  "type": "OUTFLOW",
                  "confidence": 95,
                  "isExistingCategory": true,
                  "reason": "Matches existing category 'Zakupy spożywcze' under 'Żywność'"
                }
              ],
              "bankCategoryMappings": [
                {
                  "bankCategory": "Wpływy regularne",
                  "targetCategory": "Wynagrodzenie",
                  "parentCategory": "Przychody",
                  "type": "INFLOW",
                  "confidence": 85,
                  "reason": "Bank's generic income category maps to specific salary category"
                }
              ],
              "unrecognizedPatterns": [
                {
                  "pattern": "XYZ123456",
                  "type": "OUTFLOW",
                  "reason": "Cryptic identifier, cannot determine category"
                }
              ],
              "structureOptimizations": [
                {
                  "categoryName": "Zakupy spożywcze",
                  "suggestedParent": "Żywność",
                  "currentParent": null,
                  "type": "OUTFLOW",
                  "affectedTransactionCount": 15,
                  "reason": "Cached intents suggest this category should be under 'Żywność' for better organization"
                }
              ]
            }

            CRITICAL RULES:
            1. ALWAYS check EXISTING CATEGORIES first! If a pattern matches an existing category, use it with isExistingCategory=true
            2. TYPE MATCHING: OUTFLOW patterns MUST map to OUTFLOW categories, INFLOW patterns MUST map to INFLOW categories
            3. For patterns you CANNOT recognize, add them to "unrecognizedPatterns" array
            4. confidence levels: 90-100 = auto-accept, 50-89 = needs confirmation, <50 = needs manual review
            5. Keep category names concise (2-3 words max)
            6. Return ONLY valid JSON, no markdown code blocks
            7. BANK CATEGORY MAPPINGS: For bank categories marked "NEEDS MAPPING", create a mapping to the most appropriate EXISTING category. Skip categories marked "DIRECT MATCH".
            8. NO SINGLE-CHILD HIERARCHIES: If a parent category would have only 1 subcategory, DO NOT create hierarchy - use subcategory as root category instead
            9. NO EMPTY CATEGORIES: Only include categories in categoryStructure that have at least one transaction pattern mapping to them
            10. UNIQUE NAMES PER TYPE: Category names must be unique within INFLOW and unique within OUTFLOW. Same name in both types is allowed (e.g., "Inne" in INFLOW and "Inne" in OUTFLOW is valid).
            11. STRUCTURE OPTIMIZATIONS: If CACHED PATTERN INTENTS suggest a different hierarchy than current structure, add suggestions to "structureOptimizations" array. This is OPTIONAL - only include if cached intents indicate useful reorganization.
            12. OPTIMIZATION PRIORITY: Prioritize using existing categories over suggesting reorganizations. Only suggest optimization if it would improve consistency with user's previous categorization choices.

            ⚠️ CATEGORY CONSISTENCY - CRITICAL:
            13. EVERY parentCategory used in patternMappings or bankCategoryMappings MUST exist in categoryStructure!
                - If you use "parentCategory": "Żywność", then categoryStructure.outflow MUST contain {"name": "Żywność", ...}
                - If you use "parentCategory": "Transport", then categoryStructure.outflow MUST contain {"name": "Transport", ...}
                - NULL parentCategory is allowed for root-level categories (no hierarchy)
            14. EVERY suggestedCategory in patternMappings MUST exist somewhere in categoryStructure:
                - Either as a parent category name, OR
                - As a subcategory under some parent
            15. DO NOT reference category names from the examples in system prompt unless you ALSO add them to categoryStructure!
                The example names like "Opłaty obowiązkowe", "Żywność", "Transport" are just hints - you must CREATE them in categoryStructure if you want to USE them.

            IMPORTANT: Type mismatches are FORBIDDEN! An expense (OUTFLOW) cannot go to an income category (INFLOW) and vice versa.

            CRITICAL DISTINCTION - READ CAREFULLY:
            - "pattern" = merchant/vendor name extracted from transaction (e.g., "ZABKA", "NETFLIX", "ORLEN", "XTREME FITNESS")
            - "bankCategory" = generic category assigned by bank (e.g., "TRANSAKCJA KARTĄ PŁATNICZĄ", "PRZELEW", "PŁATNOŚĆ BLIK")

            ⚠️ PATTERN MATCHING RULE - CRITICAL:
            The "pattern" field MUST be an EXACT SUBSTRING (case-insensitive) of the transaction name!
            We match patterns using: transactionName.toUpperCase().contains(pattern)

            CORRECT examples:
            - Transaction: "Urzad skarbowy w Mielcu" → pattern: "URZAD SKARBOWY W MIELCU" ✓
            - Transaction: "ZUS składki 01/2026" → pattern: "ZUS" ✓
            - Transaction: "BIEDRONKA WARSZAWA 123" → pattern: "BIEDRONKA" ✓

            WRONG examples (will NOT match!):
            - Transaction: "Urzad skarbowy w Mielcu" → pattern: "URZAD SKARBOWY MIELCU" ✗ (missing "W")
            - Transaction: "ZUS składki 01/2026" → pattern: "ZUSSKŁADKI" ✗ (no space)

            Use the SHORTEST pattern that uniquely identifies the merchant/entity.
            When in doubt, copy the key words EXACTLY as they appear in the transaction name.

            In Polish banks, 60%+ of transactions have generic bankCategory like "TRANSAKCJA KARTĄ PŁATNICZĄ".
            This is USELESS for categorization because hundreds of different merchants share the same bankCategory.

            MANDATORY RULES:
            1. You MUST create a patternMapping for EVERY pattern shown in OUTFLOW/INFLOW PATTERNS section!
               If you see 45 patterns, you should return ~40-45 patternMappings (unless some are truly unrecognizable).
            2. bankCategoryMappings should ONLY contain values from the "bank:" field shown in patterns!
               DO NOT use merchant names (like "ZABKA", "NETFLIX") as bankCategory - these belong in patternMappings!
            3. For generic bankCategories like "TRANSAKCJA KARTĄ PŁATNICZĄ", do NOT create bankCategoryMapping.
               Instead, categorize via patternMappings based on merchant names.
            """);

        return sb.toString();
    }

    /**
     * Formats category hierarchy for prompt.
     */
    private void formatCategoryHierarchy(List<ExistingCategoryStructure.CategoryNode> nodes,
                                          StringBuilder sb, String indent) {
        for (ExistingCategoryStructure.CategoryNode node : nodes) {
            sb.append(indent).append("- ").append(node.name());
            if (node.hasSubCategories()) {
                sb.append(":\n");
                for (ExistingCategoryStructure.CategoryNode sub : node.subCategories()) {
                    sb.append(indent).append("    - ").append(sub.name()).append("\n");
                }
            } else {
                sb.append("\n");
            }
        }
    }

    private String formatPatternGroup(PatternDeduplicator.PatternGroup pg) {
        String amountStr = formatAmount(pg.totalAmount());
        StringBuilder sb = new StringBuilder();

        // First line: count, amount, pattern
        sb.append(String.format("  [%d txns, %s] %s\n",
                pg.transactionCount(),
                amountStr,
                pg.pattern()));

        // Second line: sample name (original bank name)
        sb.append(String.format("    | name: \"%s\"\n",
                truncate(pg.sampleTransaction(), 50)));

        // Third line: merchant with confidence (if extracted by AI)
        // This is the KEY IMPROVEMENT for bank intermediary transactions
        if (pg.sampleMerchant() != null && !pg.sampleMerchant().isBlank()) {
            String confidenceStr = pg.averageMerchantConfidence() != null
                    ? String.format(" (%.0f%%)", pg.averageMerchantConfidence() * 100)
                    : "";
            sb.append(String.format("    | merchant: \"%s\"%s\n",
                    pg.sampleMerchant(), confidenceStr));
        }

        // Fourth line: title/description (if available)
        if (pg.sampleDescription() != null && !pg.sampleDescription().isBlank()) {
            sb.append(String.format("    | title: \"%s\"\n",
                    truncate(pg.sampleDescription(), 70)));
        }

        // Fifth line: bank category
        sb.append(String.format("    | bank: %s\n",
                pg.bankCategory() != null && !pg.bankCategory().isBlank() ? pg.bankCategory() : "-"));

        return sb.toString();
    }

    private String formatAmount(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            return String.format("%.1fk", amount.doubleValue() / 1000);
        }
        return String.format("%.0f", amount.doubleValue());
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}
