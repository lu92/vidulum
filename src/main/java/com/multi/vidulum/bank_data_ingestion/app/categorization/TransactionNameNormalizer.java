package com.multi.vidulum.bank_data_ingestion.app.categorization;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalizes transaction names to extract recognizable patterns.
 *
 * Examples:
 * - "BIEDRONKA WARSZAWA UL. MARSZALKOWSKA 123/5" → "BIEDRONKA"
 * - "ZUS SKŁADKI 01/2026 NR 123456789" → "ZUS"
 * - "PRZELEW DO JAN KOWALSKI" → "PRZELEW DO JAN KOWALSKI" (no simplification)
 */
@Component
public class TransactionNameNormalizer {

    // Known patterns that should be extracted as single-word identifiers
    private static final Set<String> KNOWN_SINGLE_WORD_PATTERNS = Set.of(
            // Grocery stores
            "BIEDRONKA", "LIDL", "ŻABKA", "ZABKA", "KAUFLAND", "AUCHAN", "CARREFOUR",
            "TESCO", "NETTO", "DINO", "STOKROTKA", "LEWIATAN", "MAKRO", "SELGROS",
            // Streaming
            "NETFLIX", "SPOTIFY", "HBO", "DISNEY", "TIDAL", "PLAYER",
            // Online shopping
            "ALLEGRO", "AMAZON", "ALIEXPRESS", "ZALANDO", "EMPIK",
            // Fuel
            "ORLEN", "BP", "SHELL", "LOTOS", "MOYA", "AMIC",
            // Transport
            "PKP", "INTERCITY", "FLIXBUS", "BOLT", "UBER",
            // Telecom
            "ORANGE", "PLAY", "PLUS", "VECTRA", "UPC", "NETIA", "INEA",
            // Utilities
            "PGE", "TAURON", "ENEA", "ENERGA", "INNOGY", "PGNIG", "VEOLIA",
            // Restaurants
            "MCDONALD", "KFC", "SUBWAY", "STARBUCKS", "WOLT", "GLOVO",
            // Home
            "IKEA", "CASTORAMA", "OBI", "JYSK",
            // Health
            "ROSSMANN", "HEBE", "MEDICOVER", "LUXMED",
            // Insurance
            "PZU", "WARTA", "ALLIANZ", "AXA", "GENERALI", "UNIQA", "LINK4",
            // Clothing
            "ZARA", "RESERVED", "SINSAY", "CROPP", "CCC", "DEICHMANN", "PEPCO",
            // Government
            "ZUS", "KRUS", "NFZ",
            // Software
            "MICROSOFT", "GOOGLE", "APPLE", "ADOBE", "GITHUB", "JETBRAINS",
            // Electronics
            "MEDIAEXPERT", "MEDIAMARKT", "KOMPUTRONIK", "MORELE"
    );

    // Known two-word patterns
    private static final Set<String> KNOWN_TWO_WORD_PATTERNS = Set.of(
            "MEDIA EXPERT", "MEDIA MARKT", "RTV EURO", "CIRCLE K", "FREE NOW",
            "BURGER KING", "PIZZA HUT", "COSTA COFFEE", "UBER EATS", "PYSZNE.PL",
            "LEROY MERLIN", "BLACK RED", "AGATA MEBLE", "LUX MED", "ENEL-MED",
            "ERGO HESTIA", "TK MAXX", "T-MOBILE", "CANAL+", "POLSAT BOX",
            "AMAZON PRIME", "APPLE MUSIC", "YOUTUBE PREMIUM",
            "URZĄD SKARBOWY", "US SKARBOWY"
    );

    // Regex patterns to remove
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile(
            "\\d{2}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}"
    );
    private static final Pattern SHORT_ACCOUNT_PATTERN = Pattern.compile(
            "\\d{8,26}"
    );
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\d{2}[/.-]\\d{2}[/.-]\\d{2,4}|\\d{2}[/.-]\\d{4}|\\d{4}[/.-]\\d{2}[/.-]\\d{2}"
    );
    private static final Pattern REFERENCE_NUMBER_PATTERN = Pattern.compile(
            "(?:NR|REF|ID|NUMER|TRANSAKCJA)[:\\s]*[A-Z0-9/-]+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "(?:UL\\.|ULICA|AL\\.|ALEJA|PL\\.|PLAC|OS\\.|OSIEDLE)[\\s.]*[A-ZĄĆĘŁŃÓŚŹŻ]+[\\s\\d/-]*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CITY_PATTERN = Pattern.compile(
            "\\b(?:WARSZAWA|KRAKÓW|KRAKOW|POZNAŃ|POZNAN|WROCŁAW|WROCLAW|GDAŃSK|GDANSK|" +
                    "ŁÓDŹ|LODZ|SZCZECIN|LUBLIN|KATOWICE|BIAŁYSTOK|BIALYSTOK|GDYNIA|CZĘSTOCHOWA|RADOM|" +
                    "SOSNOWIEC|TORUŃ|KIELCE|GLIWICE|ZABRZE|BYTOM|OLSZTYN|BIELSKO-BIAŁA|RZESZÓW)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile(
            "\\d{2}-\\d{3}"
    );
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "\\d{2}:\\d{2}(?::\\d{2})?"
    );
    private static final Pattern EXTRA_WHITESPACE = Pattern.compile("\\s{2,}");

    /**
     * Normalizes a transaction name to extract a recognizable pattern.
     *
     * @param name the original transaction name
     * @return the normalized pattern
     */
    public String normalize(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }

        String normalized = name.toUpperCase().trim();

        // Check for known single-word patterns first (fast path)
        for (String pattern : KNOWN_SINGLE_WORD_PATTERNS) {
            if (normalized.startsWith(pattern + " ") || normalized.equals(pattern)) {
                return pattern;
            }
        }

        // Check for known two-word patterns
        for (String pattern : KNOWN_TWO_WORD_PATTERNS) {
            if (normalized.startsWith(pattern + " ") || normalized.equals(pattern)) {
                return pattern;
            }
        }

        // Remove noise patterns
        normalized = ACCOUNT_NUMBER_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = SHORT_ACCOUNT_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = DATE_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = REFERENCE_NUMBER_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = ADDRESS_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = CITY_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = POSTAL_CODE_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = TIME_PATTERN.matcher(normalized).replaceAll(" ");

        // Clean up whitespace
        normalized = EXTRA_WHITESPACE.matcher(normalized).replaceAll(" ").trim();

        // If nothing left, return original
        if (normalized.isBlank()) {
            return name.toUpperCase().trim();
        }

        // Try to extract first meaningful words
        String[] words = normalized.split("\\s+");
        if (words.length == 0) {
            return normalized;
        }

        // For short results, return as-is
        if (words.length <= 3) {
            return normalized;
        }

        // For longer results, take first 3 significant words
        StringBuilder result = new StringBuilder();
        int wordCount = 0;
        for (String word : words) {
            if (word.length() >= 2 && !isNoiseWord(word)) {
                if (wordCount > 0) {
                    result.append(" ");
                }
                result.append(word);
                wordCount++;
                if (wordCount >= 3) {
                    break;
                }
            }
        }

        return result.length() > 0 ? result.toString() : normalized;
    }

    /**
     * Checks if this word is just noise and should be skipped.
     */
    private boolean isNoiseWord(String word) {
        return Set.of(
                "DO", "DLA", "NA", "OD", "W", "Z", "ZE", "PO", "ZA", "PRZED",
                "I", "ORAZ", "ALBO", "LUB", "A", "THE", "OF", "TO", "FOR",
                "NR", "REF", "ID", "NUMER", "OPERACJA", "TRANSAKCJA",
                "PRZELEW", "WPŁATA", "WYPŁATA", "ZLECENIE", "RACHUNEK"
        ).contains(word);
    }

    /**
     * Checks if a name looks like a personal transfer (hard to categorize).
     */
    public boolean isPersonalTransfer(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase();
        return upper.contains("PRZELEW DO ") ||
                upper.contains("PRZELEW OD ") ||
                upper.contains("PRZELEW NA RZECZ") ||
                upper.contains("WPŁATA OD ") ||
                upper.matches(".*\\b[A-ZĄĆĘŁŃÓŚŹŻ]+\\s+[A-ZĄĆĘŁŃÓŚŹŻ]+\\s*$");
    }
}
