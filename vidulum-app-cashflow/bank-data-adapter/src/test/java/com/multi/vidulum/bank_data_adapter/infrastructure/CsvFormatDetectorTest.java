package com.multi.vidulum.bank_data_adapter.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CsvFormatDetector.
 * Tests delimiter detection using statistical analysis.
 */
class CsvFormatDetectorTest {

    private CsvFormatDetector detector;

    @BeforeEach
    void setUp() {
        detector = new CsvFormatDetector();
    }

    @Nested
    @DisplayName("Delimiter Detection")
    class DelimiterDetection {

        @Test
        @DisplayName("Should detect semicolon delimiter for Polish bank CSV")
        void shouldDetectSemicolonForPolishBank() {
            // Given - Polish bank format with semicolons
            String csv = """
                Data księgowania;Data waluty;Nadawca / Odbiorca;Adres;Rachunek źródłowy;Kwota operacji;Waluta
                09.01.2026;09.01.2026;ALLEGRO SP. Z O.O.;POZNAN;'98124014441111001078171074;-626,00;PLN
                07.01.2026;07.01.2026;NETFLIX;DUBLIN;'98124014441111001078171074;-49,00;PLN
                """;

            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(csv);

            // Then
            assertThat(result.delimiter()).isEqualTo(";");
            assertThat(result.confidence()).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("Should detect comma delimiter for international bank CSV")
        void shouldDetectCommaForInternationalBank() {
            // Given - Revolut format with commas
            String csv = """
                Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance
                CARD_PAYMENT,Current,2026-01-09 10:30:00,2026-01-09 10:30:00,NETFLIX,-49.00,0.00,PLN,COMPLETED,1234.56
                CARD_PAYMENT,Current,2026-01-08 14:15:00,2026-01-08 14:15:00,SPOTIFY,-19.99,0.00,PLN,COMPLETED,1283.56
                """;

            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(csv);

            // Then
            assertThat(result.delimiter()).isEqualTo(",");
            assertThat(result.confidence()).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("Should detect tab delimiter")
        void shouldDetectTabDelimiter() {
            // Given - Tab-separated format
            String csv = "Date\tDescription\tAmount\tCurrency\n" +
                         "2026-01-09\tNETFLIX\t-49.00\tPLN\n" +
                         "2026-01-08\tSPOTIFY\t-19.99\tPLN\n";

            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(csv);

            // Then
            assertThat(result.delimiter()).isEqualTo("\t");
        }

        @Test
        @DisplayName("Should handle quoted values with delimiters inside")
        void shouldHandleQuotedValuesWithDelimitersInside() {
            // Given - CSV with commas inside quoted values
            String csv = """
                Date,Description,Amount,Currency
                2026-01-09,"ALLEGRO SP. Z O.O., POZNAN",-626.00,PLN
                2026-01-08,"NETFLIX, INC.",-49.00,PLN
                """;

            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(csv);

            // Then - Should still detect comma as delimiter (ignoring commas inside quotes)
            assertThat(result.delimiter()).isEqualTo(",");
        }

        @Test
        @DisplayName("Should default to comma for empty content")
        void shouldDefaultToCommaForEmptyContent() {
            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect("");

            // Then
            assertThat(result.delimiter()).isEqualTo(",");
            assertThat(result.confidence()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Real Bank CSV Formats")
    class RealBankFormats {

        @Test
        @DisplayName("Should correctly detect Bank Pekao format")
        void shouldDetectBankPekaoFormat() {
            // Given - Real Bank Pekao CSV sample
            String csv = """
                Data księgowania;Data waluty;Nadawca / Odbiorca;Adres nadawcy / odbiorcy;Rachunek źródłowy;Rachunek docelowy;Tytułem;Kwota operacji;Waluta;Numer referencyjny;Typ operacji;Kategoria
                09.01.2026;09.01.2026;UL. WOLNOSCI 23B       MIELEC;;'98124014441111001078171074;;*********0015010;-50,00;PLN;'C992600910921166;TRANSAKCJA KARTĄ PŁATNICZĄ;Opieka medyczna
                09.01.2026;09.01.2026;SHIVAGO SPOLKA Z OGRAN MIELEC;;'98124014441111001078171074;;*********0015010;-140,00;PLN;'C992600912434121;TRANSAKCJA KARTĄ PŁATNICZĄ;Uroda
                """;

            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(csv);

            // Then
            assertThat(result.delimiter()).isEqualTo(";");
            assertThat(result.confidence()).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("Should correctly detect Nest Bank format")
        void shouldDetectNestBankFormat() {
            // Given - Nest Bank format
            String csv = """
                Data operacji;Data waluty;Typ transakcji;Kwota;Waluta;Saldo po transakcji;Opis transakcji
                31.12.2025;31.12.2025;Przelew wychodzący;-500,00;PLN;12345,67;Przelew na konto
                30.12.2025;30.12.2025;Przelew przychodzący;1000,00;PLN;12845,67;Wynagrodzenie
                """;

            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(csv);

            // Then
            assertThat(result.delimiter()).isEqualTo(";");
        }

        @Test
        @DisplayName("Should correctly detect Revolut format")
        void shouldDetectRevolutFormat() {
            // Given - Revolut CSV format
            String csv = """
                Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance
                CARD_PAYMENT,Current,2026-01-09 10:30:00,2026-01-09 10:30:00,NETFLIX,-49.00,0.00,EUR,COMPLETED,1234.56
                TRANSFER,Current,2026-01-08 14:15:00,2026-01-08 14:15:00,Transfer to John,500.00,0.00,EUR,COMPLETED,1783.56
                """;

            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(csv);

            // Then
            assertThat(result.delimiter()).isEqualTo(",");
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle null input")
        void shouldHandleNullInput() {
            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(null);

            // Then
            assertThat(result.delimiter()).isEqualTo(",");
            assertThat(result.confidence()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should handle single line CSV")
        void shouldHandleSingleLine() {
            // Given
            String csv = "Date;Amount;Currency";

            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(csv);

            // Then
            assertThat(result.delimiter()).isEqualTo(";");
        }

        @Test
        @DisplayName("Should handle CSV with empty lines")
        void shouldHandleEmptyLines() {
            // Given
            String csv = """

                Data;Kwota;Waluta

                09.01.2026;-50,00;PLN
                """;

            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(csv);

            // Then
            assertThat(result.delimiter()).isEqualTo(";");
        }

        @Test
        @DisplayName("Should prefer consistent delimiter over occasional occurrences")
        void shouldPreferConsistentDelimiter() {
            // Given - CSV where semicolons are consistent but commas appear in values
            String csv = """
                Data;Opis;Kwota;Waluta
                09.01.2026;ALLEGRO SP. Z O.O., POZNAN;-626,00;PLN
                08.01.2026;NETFLIX, INC.;-49,00;PLN
                """;

            // When
            CsvFormatDetector.DetectedDelimiter result = detector.detect(csv);

            // Then - Should detect semicolon (consistent across all lines)
            assertThat(result.delimiter()).isEqualTo(";");
        }
    }
}
