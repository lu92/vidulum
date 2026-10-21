package com.multi.vidulum.bank_data_ai_categorization.api;

import com.multi.vidulum.cashflow.api.Type;

import java.util.Map;
import java.util.Optional;

/**
 * What the learned patterns recognise in a batch, keyed by the caller's own transaction ids.
 *
 * <p><b>A map of matches rather than a list of patterns, and that is the whole point of the
 * method.</b> Before this contract, two staging handlers fetched every pattern of a cash flow and
 * matched them themselves with
 * {@code name.toUpperCase().contains(pattern.normalizedPattern())} - a rule that has nothing to do
 * with the rule the patterns were <em>learned</em> under, which strips cities, streets, dates, card
 * numbers and recognises brands. The two disagreed in both directions: a three-letter pattern like
 * {@code MOL} or {@code BP} matched any name containing those letters, and a name the normalizer
 * would have recognised was missed when the pattern was not a literal substring.
 *
 * <p>An id missing from {@code matches} means "nothing recognised it" - the row carries on to the
 * ordinary path, which is what it did before.
 */
public record PatternMatches(Map<String, Match> matches) {

    public static PatternMatches none() {
        return new PatternMatches(Map.of());
    }

    public Optional<Match> forTransaction(String transactionId) {
        return Optional.ofNullable(matches.get(transactionId));
    }

    public boolean isEmpty() {
        return matches.isEmpty();
    }

    public int size() {
        return matches.size();
    }

    /**
     * @param pattern         the remembered pattern that recognised the transaction, for the log and
     *                        for the person reading why a category was chosen
     * @param category        the category it was learned against
     * @param parentCategory  where that category belongs, when the pattern remembers an intent
     * @param type            the side the pattern was learned on
     * @param confidence      0-100, as stored
     * @param source          whether a person, the model or the seed list taught it
     */
    public record Match(
            String pattern,
            String category,
            String parentCategory,
            Type type,
            int confidence,
            PatternSource source
    ) {
    }
}
