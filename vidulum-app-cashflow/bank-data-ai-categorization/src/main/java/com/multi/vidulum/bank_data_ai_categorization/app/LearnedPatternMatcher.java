package com.multi.vidulum.bank_data_ai_categorization.app;

import com.multi.vidulum.bank_data_ai_categorization.api.PatternMatches;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionToMatch;
import com.multi.vidulum.bank_data_ai_categorization.domain.PatternMapping;
import com.multi.vidulum.bank_data_ai_categorization.domain.PatternMappingRepository;
import com.multi.vidulum.common.CashFlowId;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Offers transactions to what a cash flow has already taught this module - under one rule, which is
 * the point of this class existing.
 *
 * <p><b>Three candidate rules, and only one of them is right.</b> Two staging handlers used to match
 * with {@code name.toUpperCase().contains(pattern)}; the patterns themselves are produced by
 * {@link TransactionNameNormalizer}. Measured against real data, neither is the relationship:
 *
 * <table border="1">
 *   <caption>what each rule does with real statement lines</caption>
 *   <tr><th>rule</th><th>ZAKŁAD UBEZPIECZEŃ SPOŁECZNYCH ZUS ↔ ZUS</th><th>DEMOLKA SERWIS ↔ MOL</th></tr>
 *   <tr><td>{@code contains}</td><td>matches (right)</td><td><b>matches (wrong)</b></td></tr>
 *   <tr><td>{@code normalize(name).equals(pattern)}</td><td><b>misses (wrong)</b></td><td>rejects (right)</td></tr>
 *   <tr><td><b>whole-word containment</b></td><td>matches</td><td>rejects</td></tr>
 * </table>
 *
 * <p>The second rule looks purer and is wrong, because the normalizer only recognises a brand at the
 * <em>start</em> of a name ({@code startsWith(brand + " ")}), and banks write the acronym last often
 * enough to matter. The third keeps every case the first got right and drops the ones it got wrong:
 * a learned pattern is always either a brand token or a normalized phrase, and both appear as whole
 * words in the names they were learned from. Short patterns are where this bites - the seed list has
 * {@code BP}, {@code CCC}, {@code DOZ}, {@code AXA}, {@code MOL}.
 *
 * <p>Boundaries are checked with {@link Character#isLetterOrDigit} rather than a {@code \b} regex,
 * because {@code \b} keys on ASCII word characters and these names are full of Ł, Ż and Ę.
 *
 * <p>Neither old failure announced itself: a wrongly matched row looks exactly like a correctly
 * matched one to the person importing a statement.
 */
@Slf4j
@Component
@AllArgsConstructor
public class LearnedPatternMatcher {

    private final PatternMappingRepository patternMappingRepository;
    private final TransactionNameNormalizer normalizer;

    public PatternMatches match(CashFlowId cashFlowId, List<TransactionToMatch> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return PatternMatches.none();
        }

        List<PatternMapping> remembered = patternMappingRepository.findAllByCashFlowId(cashFlowId.id());
        if (remembered.isEmpty()) {
            return PatternMatches.none();
        }

        Map<String, PatternMatches.Match> matches = new HashMap<>();
        for (TransactionToMatch transaction : transactions) {
            if (transaction.name() == null) {
                continue;
            }
            String name = transaction.name().toUpperCase();

            // most confident wins, as it did before - two patterns can both recognise a name
            Optional<PatternMapping> hit = remembered.stream()
                    .filter(pattern -> pattern.categoryType() == transaction.type())
                    .filter(pattern -> containsAsWholeWord(name, pattern.normalizedPattern()))
                    .max(Comparator.comparingDouble(PatternMapping::confidenceScore));

            hit.ifPresent(pattern -> {
                matches.put(transaction.id(), new PatternMatches.Match(
                        pattern.normalizedPattern(),
                        pattern.suggestedCategory(),
                        pattern.intendedParentCategory(),
                        pattern.categoryType(),
                        pattern.confidencePercentage(),
                        pattern.source()));
                patternMappingRepository.recordUsage(pattern.id());
            });
        }

        log.debug("Learned patterns recognised {} of {} transactions for cash flow {}",
                matches.size(), transactions.size(), cashFlowId.id());
        return new PatternMatches(matches);
    }

    /**
     * True when {@code pattern} appears in {@code name} as a whole word.
     *
     * <p>A letter or digit on either side means it is part of a longer word and not an occurrence of
     * the pattern: {@code MOL} is in {@code DEMOLKA} the way {@code ART} is in {@code PARTNER}.
     */
    private static boolean containsAsWholeWord(String name, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = name.indexOf(pattern, from);
            if (at < 0) {
                return false;
            }
            boolean letterBefore = at > 0 && Character.isLetterOrDigit(name.charAt(at - 1));
            int after = at + pattern.length();
            boolean letterAfter = after < name.length() && Character.isLetterOrDigit(name.charAt(after));
            if (!letterBefore && !letterAfter) {
                return true;
            }
            from = at + 1;
        }
    }
}
