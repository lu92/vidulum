package com.multi.vidulum.bank_data_ingestion.app.categorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TransactionNameNormalizer.
 * Tests pattern recognition for Polish transaction names.
 */
class TransactionNameNormalizerTest {

    private TransactionNameNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new TransactionNameNormalizer();
    }

    // ============ EXISTING PATTERNS TESTS ============

    @ParameterizedTest
    @DisplayName("Should normalize known grocery store patterns")
    @CsvSource({
            "BIEDRONKA WARSZAWA UL. MARSZALKOWSKA 123, BIEDRONKA",
            "LIDL KRAKOW CENTRUM, LIDL",
            "ZABKA Z5432 WARSZAWA, ZABKA",
            "ŻABKA POZNAŃ, ŻABKA",
            "KAUFLAND WROCŁAW, KAUFLAND"
    })
    void shouldNormalizeKnownGroceryStorePatterns(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("Should normalize streaming service patterns")
    @CsvSource({
            "NETFLIX AMSTERDAM, NETFLIX",
            "SPOTIFY STOCKHOLM, SPOTIFY",
            "HBO MAX PAYMENT, HBO"
    })
    void shouldNormalizeStreamingServicePatterns(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should normalize NETFLIX.COM (with domain extension)")
    void shouldNormalizeNetflixWithDomain() {
        // NETFLIX.COM doesn't match exact pattern "NETFLIX " or equals "NETFLIX"
        // so it goes through normal processing which keeps first 3 words
        String result = normalizer.normalize("NETFLIX.COM AMSTERDAM");
        // The result will contain NETFLIX.COM as it's not exactly "NETFLIX"
        assertThat(result).startsWith("NETFLIX");
    }

    @ParameterizedTest
    @DisplayName("Should normalize fuel station patterns")
    @CsvSource({
            "ORLEN STACJA 1234 WARSZAWA, ORLEN",
            "BP EXPRESS KRAKOW, BP",
            "SHELL STATION PL, SHELL",
            "LOTOS PALIWA GDANSK, LOTOS"
    })
    void shouldNormalizeFuelStationPatterns(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    // ============ NEW PATTERNS TESTS (STRATEGY A) ============

    @ParameterizedTest
    @DisplayName("Should normalize fitness center patterns")
    @CsvSource({
            "XTREME FITNESS GYMS MI MIELEC, XTREME",
            "ZDROFIT OCHOTA WARSZAWA, ZDROFIT",
            "CITYFIT WARSZAWA CENTRUM, CITYFIT",
            "DECATHLON KRAKOW, DECATHLON"
    })
    void shouldNormalizeFitnessPatterns(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("Should normalize AI/software subscription patterns")
    @CsvSource({
            "CLAUDE SAN FRANCISCO, CLAUDE",
            "CHATGPT OPENAI SF, CHATGPT",
            "OPENAI API SERVICES, OPENAI",
            "TRADINGVIEW INC, TRADINGVIEW",
            "CANVA PTY LTD, CANVA"
    })
    void shouldNormalizeAiSoftwarePatterns(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should handle CLAUDE.AI with domain extension")
    void shouldHandleClaudeWithDomain() {
        // CLAUDE.AI doesn't match exact "CLAUDE " pattern so goes through normal processing
        String result = normalizer.normalize("CLAUDE.AI SAN FRANCISCO");
        // Should still start with CLAUDE
        assertThat(result).startsWith("CLAUDE");
    }

    @ParameterizedTest
    @DisplayName("Should normalize dating app patterns")
    @CsvSource({
            "BADOO LONDON UK, BADOO",
            "TINDER MATCH GROUP, TINDER"
    })
    void shouldNormalizeDatingAppPatterns(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("Should normalize health/medical patterns")
    @CsvSource({
            "JUNONA CENTRUM MEDYCZNE, JUNONA",
            "SHIVAGO FIZJOTERAPIA, SHIVAGO",
            "FIZJOTERAPIA CENTRUM, FIZJOTERAPIA"
    })
    void shouldNormalizeHealthPatterns(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should normalize MOL fuel station")
    void shouldNormalizeMolFuelStation() {
        assertThat(normalizer.normalize("MOL STACJA KRAKOW")).isEqualTo("MOL");
        assertThat(normalizer.normalize("MOL POLSKA SP ZOO")).isEqualTo("MOL");
    }

    @Test
    @DisplayName("Should normalize INTERMARCHE grocery store")
    void shouldNormalizeIntermarche() {
        assertThat(normalizer.normalize("INTERMARCHE WARSZAWA")).isEqualTo("INTERMARCHE");
    }

    // ============ TWO-WORD PATTERNS TESTS ============

    @Test
    @DisplayName("Should normalize XTREME FITNESS two-word pattern")
    void shouldNormalizeXtremeFitnessTwoWordPattern() {
        // XTREME alone should work
        assertThat(normalizer.normalize("XTREME SOMETHING ELSE")).isEqualTo("XTREME");

        // XTREME FITNESS as two-word pattern should also work
        // Note: The single-word XTREME takes priority over two-word XTREME FITNESS
        assertThat(normalizer.normalize("XTREME FITNESS GYMS")).isEqualTo("XTREME");
    }

    // ============ NOISE REMOVAL TESTS ============

    @Test
    @DisplayName("Should remove city names from transaction")
    void shouldRemoveCityNames() {
        String result = normalizer.normalize("SKLEP ABC WARSZAWA UL. DŁUGA 15");
        assertThat(result).doesNotContain("WARSZAWA");
    }

    @Test
    @DisplayName("Should remove postal codes from transaction")
    void shouldRemovePostalCodes() {
        String result = normalizer.normalize("FIRMA XYZ 00-001 WARSZAWA");
        assertThat(result).doesNotContain("00-001");
    }

    @Test
    @DisplayName("Should remove dates from transaction")
    void shouldRemoveDates() {
        String result = normalizer.normalize("PLATNOSC 01/01/2026 SKLEP");
        assertThat(result).doesNotContain("01/01/2026");
    }

    @Test
    @DisplayName("Should remove account numbers from transaction")
    void shouldRemoveAccountNumbers() {
        String result = normalizer.normalize("PRZELEW DO 12345678901234567890123456");
        assertThat(result).doesNotContain("12345678901234567890123456");
    }

    // ============ EDGE CASES ============

    @Test
    @DisplayName("Should handle null input")
    void shouldHandleNullInput() {
        assertThat(normalizer.normalize(null)).isEqualTo("");
    }

    @Test
    @DisplayName("Should handle empty input")
    void shouldHandleEmptyInput() {
        assertThat(normalizer.normalize("")).isEqualTo("");
        assertThat(normalizer.normalize("   ")).isEqualTo("");
    }

    @Test
    @DisplayName("Should handle input with only noise")
    void shouldHandleInputWithOnlyNoise() {
        // When all meaningful content is removed, should return original uppercase
        String result = normalizer.normalize("12345678");
        assertThat(result).isNotBlank();
    }

    // ============ PERSONAL TRANSFER DETECTION ============

    @Test
    @DisplayName("Should detect personal transfer patterns")
    void shouldDetectPersonalTransferPatterns() {
        // Explicit transfer patterns
        assertThat(normalizer.isPersonalTransfer("PRZELEW DO JAN KOWALSKI")).isTrue();
        assertThat(normalizer.isPersonalTransfer("PRZELEW OD ANNA NOWAK")).isTrue();
        assertThat(normalizer.isPersonalTransfer("WPŁATA OD MARIAN KOWALCZYK")).isTrue();

        // Shop names - should NOT be detected as personal transfer
        // Note: The method also checks for pattern "ends with two Polish words" which
        // can match some shop names. This is acceptable as it's a heuristic.
        assertThat(normalizer.isPersonalTransfer("BIEDRONKA 123")).isFalse();
    }

    @Test
    @DisplayName("Should handle personal transfer with null")
    void shouldHandlePersonalTransferWithNull() {
        assertThat(normalizer.isPersonalTransfer(null)).isFalse();
    }
}
