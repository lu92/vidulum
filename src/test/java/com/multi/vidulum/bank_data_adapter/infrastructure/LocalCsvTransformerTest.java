package com.multi.vidulum.bank_data_adapter.infrastructure;

import com.multi.vidulum.bank_data_adapter.domain.MappingRules;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules.ColumnMapping;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules.TransformationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for LocalCsvTransformer focusing on currency handling.
 */
@DisplayName("LocalCsvTransformer")
class LocalCsvTransformerTest {

    private LocalCsvTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new LocalCsvTransformer();
    }

    @Nested
    @DisplayName("Currency Fallback")
    class CurrencyFallback {

        @Test
        @DisplayName("Should use PLN as default currency when no currency mapping exists")
        void shouldUsePlnAsDefaultCurrency() {
            // given - CSV without currency column, mapping rules without currency mapping
            String csv = """
                Data operacji,Kwota,Opis
                2023-06-15,5000.00,Wyplata
                2023-07-01,-2000.00,Czynsz
                """;

            MappingRules rules = MappingRules.builder()
                .bankName("Test Bank")
                .bankCountry("PL")
                .dateFormat("yyyy-MM-dd")
                .delimiter(",")
                .headerRowIndex(0)
                .columnMappings(List.of(
                    ColumnMapping.builder()
                        .sourceColumn("Data operacji")
                        .sourceIndex(0)
                        .targetField("operationDate")
                        .transformationType(TransformationType.DATE_PARSE)
                        .transformationParams(Map.of("format", "yyyy-MM-dd"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("amount")
                        .transformationType(TransformationType.AMOUNT_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Opis")
                        .sourceIndex(2)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            assertThat(result.csvContent()).contains("PLN");

            String[] lines = result.csvContent().split("\n");
            assertThat(lines).hasSize(3); // header + 2 data rows

            // Check first data row contains PLN
            String firstDataRow = lines[1];
            assertThat(firstDataRow).contains(",PLN,");
        }

        @Test
        @DisplayName("Should use EUR for German bank")
        void shouldUseEurForGermanBank() {
            // given - German bank CSV
            String csv = """
                Datum,Betrag,Beschreibung
                2023-06-15,5000.00,Gehalt
                """;

            MappingRules rules = MappingRules.builder()
                .bankName("Deutsche Bank")
                .bankCountry("DE")
                .dateFormat("yyyy-MM-dd")
                .delimiter(",")
                .headerRowIndex(0)
                .columnMappings(List.of(
                    ColumnMapping.builder()
                        .sourceColumn("Datum")
                        .sourceIndex(0)
                        .targetField("operationDate")
                        .transformationType(TransformationType.DATE_PARSE)
                        .transformationParams(Map.of("format", "yyyy-MM-dd"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Betrag")
                        .sourceIndex(1)
                        .targetField("amount")
                        .transformationType(TransformationType.AMOUNT_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Betrag")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Beschreibung")
                        .sourceIndex(2)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            assertThat(result.csvContent()).contains(",EUR,");
        }

        @Test
        @DisplayName("Should use GBP for UK bank")
        void shouldUseGbpForUkBank() {
            // given - UK bank CSV
            String csv = """
                Date,Amount,Description
                2023-06-15,5000.00,Salary
                """;

            MappingRules rules = MappingRules.builder()
                .bankName("Barclays")
                .bankCountry("GB")
                .dateFormat("yyyy-MM-dd")
                .delimiter(",")
                .headerRowIndex(0)
                .columnMappings(List.of(
                    ColumnMapping.builder()
                        .sourceColumn("Date")
                        .sourceIndex(0)
                        .targetField("operationDate")
                        .transformationType(TransformationType.DATE_PARSE)
                        .transformationParams(Map.of("format", "yyyy-MM-dd"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Amount")
                        .sourceIndex(1)
                        .targetField("amount")
                        .transformationType(TransformationType.AMOUNT_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Amount")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Description")
                        .sourceIndex(2)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            assertThat(result.csvContent()).contains(",GBP,");
        }

        @Test
        @DisplayName("Should use PLN when bankCountry is null")
        void shouldUsePlnWhenBankCountryIsNull() {
            // given - no country info
            String csv = """
                Date,Amount,Name
                2023-06-15,100.00,Test
                """;

            MappingRules rules = MappingRules.builder()
                .bankName("Unknown Bank")
                .bankCountry(null) // No country
                .dateFormat("yyyy-MM-dd")
                .delimiter(",")
                .headerRowIndex(0)
                .columnMappings(List.of(
                    ColumnMapping.builder()
                        .sourceColumn("Date")
                        .sourceIndex(0)
                        .targetField("operationDate")
                        .transformationType(TransformationType.DATE_PARSE)
                        .transformationParams(Map.of("format", "yyyy-MM-dd"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Amount")
                        .sourceIndex(1)
                        .targetField("amount")
                        .transformationType(TransformationType.AMOUNT_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Amount")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Name")
                        .sourceIndex(2)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            assertThat(result.csvContent()).contains(",PLN,");
        }
    }

    @Nested
    @DisplayName("Currency Extraction")
    class CurrencyExtraction {

        @Test
        @DisplayName("Should extract currency from dedicated column")
        void shouldExtractCurrencyFromColumn() {
            // given - CSV with currency column
            String csv = """
                Data,Kwota,Waluta,Opis
                2023-06-15,5000.00,EUR,Transfer
                """;

            MappingRules rules = MappingRules.builder()
                .bankName("Test Bank")
                .bankCountry("PL")
                .dateFormat("yyyy-MM-dd")
                .delimiter(",")
                .headerRowIndex(0)
                .columnMappings(List.of(
                    ColumnMapping.builder()
                        .sourceColumn("Data")
                        .sourceIndex(0)
                        .targetField("operationDate")
                        .transformationType(TransformationType.DATE_PARSE)
                        .transformationParams(Map.of("format", "yyyy-MM-dd"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("amount")
                        .transformationType(TransformationType.AMOUNT_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Waluta")
                        .sourceIndex(2)
                        .targetField("currency")
                        .transformationType(TransformationType.CURRENCY_EXTRACT)
                        .transformationParams(Map.of("default", "PLN"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Opis")
                        .sourceIndex(3)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            assertThat(result.csvContent()).contains(",EUR,");
        }

        @Test
        @DisplayName("Should use default currency when column is empty")
        void shouldUseDefaultCurrencyWhenColumnIsEmpty() {
            // given - CSV with empty currency column
            String csv = """
                Data,Kwota,Waluta,Opis
                2023-06-15,5000.00,,Transfer
                """;

            MappingRules rules = MappingRules.builder()
                .bankName("Test Bank")
                .bankCountry("PL")
                .dateFormat("yyyy-MM-dd")
                .delimiter(",")
                .headerRowIndex(0)
                .columnMappings(List.of(
                    ColumnMapping.builder()
                        .sourceColumn("Data")
                        .sourceIndex(0)
                        .targetField("operationDate")
                        .transformationType(TransformationType.DATE_PARSE)
                        .transformationParams(Map.of("format", "yyyy-MM-dd"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("amount")
                        .transformationType(TransformationType.AMOUNT_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Waluta")
                        .sourceIndex(2)
                        .targetField("currency")
                        .transformationType(TransformationType.CURRENCY_EXTRACT)
                        .transformationParams(Map.of("default", "USD"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Opis")
                        .sourceIndex(3)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            // CURRENCY_EXTRACT with empty value returns the default from transformationParams
            assertThat(result.csvContent()).contains(",USD,");
        }

        @Test
        @DisplayName("Should extract currency code from amount string like '5000 PLN'")
        void shouldExtractCurrencyFromAmountString() {
            // given - Currency embedded in amount
            String csv = """
                Data,Kwota,Opis
                2023-06-15,5000 CHF,Transfer
                """;

            MappingRules rules = MappingRules.builder()
                .bankName("Swiss Bank")
                .bankCountry("CH")
                .dateFormat("yyyy-MM-dd")
                .delimiter(",")
                .headerRowIndex(0)
                .columnMappings(List.of(
                    ColumnMapping.builder()
                        .sourceColumn("Data")
                        .sourceIndex(0)
                        .targetField("operationDate")
                        .transformationType(TransformationType.DATE_PARSE)
                        .transformationParams(Map.of("format", "yyyy-MM-dd"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("amount")
                        .transformationType(TransformationType.AMOUNT_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("currency")
                        .transformationType(TransformationType.CURRENCY_EXTRACT)
                        .transformationParams(Map.of("default", "CHF"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Opis")
                        .sourceIndex(2)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            // CURRENCY_EXTRACT finds "CHF" in "5000 CHF"
            assertThat(result.csvContent()).contains(",CHF,");
        }
    }

    @Nested
    @DisplayName("Country Currency Mapping")
    class CountryCurrencyMapping {

        @Test
        @DisplayName("Should map eurozone countries to EUR")
        void shouldMapEurozoneCountriesToEur() {
            List<String> eurozoneCountries = List.of("DE", "FR", "ES", "IT", "NL", "AT", "BE", "FI", "IE", "PT", "GR");

            for (String country : eurozoneCountries) {
                // given
                String csv = """
                    Date,Amount,Name
                    2023-06-15,100.00,Test
                    """;

                MappingRules rules = createMinimalRules(country);

                // when
                LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

                // then
                assertThat(result.success())
                    .as("Transformation should succeed for country: " + country)
                    .isTrue();
                assertThat(result.csvContent())
                    .as("Should use EUR for country: " + country)
                    .contains(",EUR,");
            }
        }

        @Test
        @DisplayName("Should map Scandinavian countries to their currencies")
        void shouldMapScandinavianCountries() {
            Map<String, String> expectedCurrencies = Map.of(
                "SE", "SEK",
                "NO", "NOK",
                "DK", "DKK"
            );

            for (Map.Entry<String, String> entry : expectedCurrencies.entrySet()) {
                // given
                String csv = """
                    Date,Amount,Name
                    2023-06-15,100.00,Test
                    """;

                MappingRules rules = createMinimalRules(entry.getKey());

                // when
                LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

                // then
                assertThat(result.success())
                    .as("Transformation should succeed for country: " + entry.getKey())
                    .isTrue();
                assertThat(result.csvContent())
                    .as("Should use " + entry.getValue() + " for country: " + entry.getKey())
                    .contains("," + entry.getValue() + ",");
            }
        }

        @Test
        @DisplayName("Should map other countries to their currencies")
        void shouldMapOtherCountries() {
            Map<String, String> expectedCurrencies = Map.of(
                "US", "USD",
                "CH", "CHF",
                "CZ", "CZK",
                "UK", "GBP"
            );

            for (Map.Entry<String, String> entry : expectedCurrencies.entrySet()) {
                // given
                String csv = """
                    Date,Amount,Name
                    2023-06-15,100.00,Test
                    """;

                MappingRules rules = createMinimalRules(entry.getKey());

                // when
                LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

                // then
                assertThat(result.success())
                    .as("Transformation should succeed for country: " + entry.getKey())
                    .isTrue();
                assertThat(result.csvContent())
                    .as("Should use " + entry.getValue() + " for country: " + entry.getKey())
                    .contains("," + entry.getValue() + ",");
            }
        }

        private MappingRules createMinimalRules(String countryCode) {
            return MappingRules.builder()
                .bankName("Test Bank")
                .bankCountry(countryCode)
                .dateFormat("yyyy-MM-dd")
                .delimiter(",")
                .headerRowIndex(0)
                .columnMappings(List.of(
                    ColumnMapping.builder()
                        .sourceColumn("Date")
                        .sourceIndex(0)
                        .targetField("operationDate")
                        .transformationType(TransformationType.DATE_PARSE)
                        .transformationParams(Map.of("format", "yyyy-MM-dd"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Amount")
                        .sourceIndex(1)
                        .targetField("amount")
                        .transformationType(TransformationType.AMOUNT_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Amount")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Name")
                        .sourceIndex(2)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                ))
                .build();
        }
    }
}
