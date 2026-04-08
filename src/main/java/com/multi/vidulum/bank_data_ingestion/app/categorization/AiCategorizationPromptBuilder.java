package com.multi.vidulum.bank_data_ingestion.app.categorization;

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
     */
    public String getSystemPrompt() {
        return """
            You are a financial transaction categorization expert for Polish personal finance.
            Your task is to analyze transaction patterns and suggest an optimal nested category structure.

            Guidelines:
            1. Create hierarchical categories (parent → subcategories) ONLY when there are 2+ subcategories
            2. Use Polish category names
            3. Keep structure practical - 5-8 parent categories max
            4. Each subcategory should be specific enough to be useful
            5. Consider transaction frequency and amounts when prioritizing
            6. FLATTEN single-child hierarchies: if a parent would have only 1 subcategory, DON'T create the parent - use the subcategory directly as a root category
            7. Only include categories that have actual transactions mapping to them

            Category types:
            - OUTFLOW: Expenses (wydatki)
            - INFLOW: Income (przychody)

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

            You MUST respond with valid JSON only, no markdown, no explanation.
            """;
    }

    /**
     * Builds the user prompt with pattern groups.
     *
     * @param patternGroups the deduplicated pattern groups
     * @param categoryStructure existing categories with type and hierarchy
     * @return the user prompt
     */
    public String buildUserPrompt(List<PatternDeduplicator.PatternGroup> patternGroups,
                                   ExistingCategoryStructure categoryStructure) {

        StringBuilder sb = new StringBuilder();

        sb.append("Analyze these transaction patterns and suggest categories:\n\n");

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

            IMPORTANT: Type mismatches are FORBIDDEN! An expense (OUTFLOW) cannot go to an income category (INFLOW) and vice versa.
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

        // Second line: sample name
        sb.append(String.format("    | name: \"%s\"\n",
                truncate(pg.sampleTransaction(), 50)));

        // Third line: title/description (if available) - THIS IS THE KEY IMPROVEMENT!
        if (pg.sampleDescription() != null && !pg.sampleDescription().isBlank()) {
            sb.append(String.format("    | title: \"%s\"\n",
                    truncate(pg.sampleDescription(), 70)));
        }

        // Fourth line: bank category
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
