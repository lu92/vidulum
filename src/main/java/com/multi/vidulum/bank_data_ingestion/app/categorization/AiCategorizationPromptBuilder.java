package com.multi.vidulum.bank_data_ingestion.app.categorization;

import com.multi.vidulum.cashflow.domain.Type;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
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
            1. Create hierarchical categories (parent → subcategories)
            2. Use Polish category names
            3. Keep structure practical - 5-8 parent categories max
            4. Each subcategory should be specific enough to be useful
            5. Consider transaction frequency and amounts when prioritizing

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
     * @param existingCategories existing categories in the CashFlow (to avoid duplicates)
     * @return the user prompt
     */
    public String buildUserPrompt(List<PatternDeduplicator.PatternGroup> patternGroups,
                                   List<String> existingCategories) {

        StringBuilder sb = new StringBuilder();

        sb.append("Analyze these transaction patterns and suggest categories:\n\n");

        // Add existing categories context
        if (existingCategories != null && !existingCategories.isEmpty()) {
            sb.append("EXISTING CATEGORIES (use these if patterns match):\n");
            for (String cat : existingCategories) {
                sb.append("  - ").append(cat).append("\n");
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

        // Expected output format
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
                  "confidence": 95
                }
              ]
            }

            Rules:
            1. confidence: 90-100 = auto-accept, 50-89 = suggested, <50 = needs manual
            2. For patterns you don't recognize, set confidence < 50
            3. Match existing categories when possible
            4. Keep category names concise (2-3 words max)
            5. Return ONLY valid JSON, no markdown code blocks
            """);

        return sb.toString();
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
