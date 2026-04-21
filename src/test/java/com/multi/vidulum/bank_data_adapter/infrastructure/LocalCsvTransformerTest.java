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

    @Nested
    @DisplayName("Name Field Fallback")
    class NameFieldFallback {

        @Test
        @DisplayName("Should use description as name when name mapping is missing")
        void shouldUseDescriptionAsNameWhenNameMappingIsMissing() {
            // given - CSV with description but no name column mapped
            String csv = """
                Data,Kwota,Opis
                2023-06-15,100.00,Przelew za czynsz
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
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Opis")
                        .sourceIndex(2)
                        .targetField("description")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                    // NOTE: No name mapping!
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            // Name should be populated from description
            String[] lines = result.csvContent().split("\n");
            assertThat(lines).hasSize(2);
            // Format: bankTransactionId,name,description,...
            String dataRow = lines[1];
            assertThat(dataRow).contains("Przelew za czynsz");
        }

        @Test
        @DisplayName("Should use bankCategory as name when name and description are missing")
        void shouldUseBankCategoryAsNameWhenNameAndDescriptionMissing() {
            // given - CSV with only bankCategory
            String csv = """
                Data,Kwota,Kategoria
                2023-06-15,100.00,Przelewy wychodzace
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
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kategoria")
                        .sourceIndex(2)
                        .targetField("bankCategory")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                    // NOTE: No name or description mapping!
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            String[] lines = result.csvContent().split("\n");
            assertThat(lines).hasSize(2);
            // Name should be populated from bankCategory
            String dataRow = lines[1];
            assertThat(dataRow).contains("Przelewy wychodzace");
        }

        @Test
        @DisplayName("Should generate placeholder name when no name, description or bankCategory")
        void shouldGeneratePlaceholderNameWhenNothingAvailable() {
            // given - CSV with only required fields
            String csv = """
                Data,Kwota
                2023-06-15,100.00
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
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build()
                    // NOTE: No name, description or bankCategory mapping!
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            String[] lines = result.csvContent().split("\n");
            assertThat(lines).hasSize(2);
            // Name should be generated placeholder
            String dataRow = lines[1];
            assertThat(dataRow).contains("Transaction 100");
        }

        @Test
        @DisplayName("Should NOT use fallback when name mapping exists and has value")
        void shouldNotUseFallbackWhenNameMappingExists() {
            // given - CSV with name column mapped
            String csv = """
                Data,Kwota,Kontrahent,Opis
                2023-06-15,100.00,Firma ABC,Platnosc za fakture
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
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kontrahent")
                        .sourceIndex(2)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Opis")
                        .sourceIndex(3)
                        .targetField("description")
                        .transformationType(TransformationType.DIRECT)
                        .build()
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            String[] lines = result.csvContent().split("\n");
            assertThat(lines).hasSize(2);
            // Name should be "Firma ABC", not "Platnosc za fakture"
            String dataRow = lines[1];
            assertThat(dataRow).contains("Firma ABC");
            // Both name and description should be present
            assertThat(dataRow).contains("Platnosc za fakture");
        }
    }

    @Nested
    @DisplayName("Amount Absolute Value")
    class AmountAbsoluteValue {

        @Test
        @DisplayName("Should convert negative amount to positive (absolute value)")
        void shouldConvertNegativeAmountToPositive() {
            // given - CSV with negative amounts
            String csv = """
                Data,Kwota,Nazwa
                2023-06-15,-100.00,Przelew wychodzacy
                2023-06-16,-2500.50,Oplata za usluge
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
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Nazwa")
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
            String[] lines = result.csvContent().split("\n");
            assertThat(lines).hasSize(3); // header + 2 data rows

            // First row: -100.00 should become 100.00 (positive)
            String firstRow = lines[1];
            assertThat(firstRow).contains(",100.00,"); // amount is positive
            assertThat(firstRow).contains(",OUTFLOW,"); // type detected from negative sign
            assertThat(firstRow).doesNotContain(",-100");

            // Second row: -2500.50 should become 2500.50 (positive)
            String secondRow = lines[2];
            assertThat(secondRow).contains(",2500.50,"); // amount is positive
            assertThat(secondRow).contains(",OUTFLOW,"); // type detected from negative sign
            assertThat(secondRow).doesNotContain(",-2500");
        }

        @Test
        @DisplayName("Should keep positive amount unchanged")
        void shouldKeepPositiveAmountUnchanged() {
            // given - CSV with positive amounts
            String csv = """
                Data,Kwota,Nazwa
                2023-06-15,5000.00,Wyplata
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
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Nazwa")
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
            String[] lines = result.csvContent().split("\n");
            assertThat(lines).hasSize(2);

            String dataRow = lines[1];
            assertThat(dataRow).contains(",5000.00,"); // amount stays positive
            assertThat(dataRow).contains(",INFLOW,"); // type detected from positive sign
        }

        @Test
        @DisplayName("Should handle mixed positive and negative amounts")
        void shouldHandleMixedAmounts() {
            // given - CSV with mixed amounts
            String csv = """
                Data,Kwota,Nazwa
                2023-06-15,5000.00,Wyplata
                2023-06-16,-1500.00,Czynsz
                2023-06-17,200.00,Zwrot
                2023-06-18,-50.00,Prowizja
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
                        .sourceColumn("Kwota")
                        .sourceIndex(1)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "1"))
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Nazwa")
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
            String[] lines = result.csvContent().split("\n");
            assertThat(lines).hasSize(5); // header + 4 data rows

            // All amounts should be positive, type determines direction
            assertThat(lines[1]).contains(",5000.00,").contains(",INFLOW,");
            assertThat(lines[2]).contains(",1500.00,").contains(",OUTFLOW,");
            assertThat(lines[3]).contains(",200.00,").contains(",INFLOW,");
            assertThat(lines[4]).contains(",50.00,").contains(",OUTFLOW,");

            // No negative amounts in output
            for (int i = 1; i < lines.length; i++) {
                assertThat(lines[i]).doesNotContain(",-");
            }
        }
    }

    @Nested
    @DisplayName("Schema-First: bankCategory vs paymentMethod Distinction")
    class BankCategoryVsPaymentMethod {

        @Test
        @DisplayName("Should correctly map Pekao-style CSV with both Kategoria and Typ operacji columns")
        void shouldCorrectlyMapPekaoStyleCsv() {
            // given - Pekao-style CSV with both category types:
            // "Typ operacji" = HOW (payment method) - e.g., "Płatność kartą"
            // "Kategoria" = WHAT (semantic category) - e.g., "Sport", "Zakupy"
            String csv = """
                Data księgowania;Data waluty;Nadawca / Odbiorca;Kwota operacji;Waluta;Typ operacji;Kategoria
                15.12.2025;15.12.2025;Decathlon Polska;-199.99;PLN;Płatność kartą;Sport
                16.12.2025;16.12.2025;Biedronka;-85.50;PLN;Płatność kartą;Zakupy
                17.12.2025;17.12.2025;ZUS;-1200.00;PLN;Przelew wychodzący;Rachunki
                """;

            // Schema-First approach should produce these mappings:
            // - Column 5 "Typ operacji" → paymentMethod (PAYMENT_METHOD_NORMALIZE)
            // - Column 6 "Kategoria" → bankCategory (DIRECT)
            MappingRules rules = MappingRules.builder()
                .bankName("Bank Pekao")
                .bankCountry("PL")
                .language("pl")
                .dateFormat("dd.MM.yyyy")
                .delimiter(";")
                .headerRowIndex(0)
                .columnMappings(List.of(
                    ColumnMapping.builder()
                        .sourceColumn("Data księgowania")
                        .sourceIndex(0)
                        .targetField("operationDate")
                        .transformationType(TransformationType.DATE_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Nadawca / Odbiorca")
                        .sourceIndex(2)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota operacji")
                        .sourceIndex(3)
                        .targetField("amount")
                        .transformationType(TransformationType.AMOUNT_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Waluta")
                        .sourceIndex(4)
                        .targetField("currency")
                        .transformationType(TransformationType.CURRENCY_EXTRACT)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota operacji")
                        .sourceIndex(3)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "3"))
                        .build(),
                    // THIS IS THE KEY DISTINCTION - Schema-First should produce:
                    ColumnMapping.builder()
                        .sourceColumn("Typ operacji")
                        .sourceIndex(5)
                        .targetField("paymentMethod")  // HOW payment was made
                        .transformationType(TransformationType.PAYMENT_METHOD_NORMALIZE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kategoria")
                        .sourceIndex(6)
                        .targetField("bankCategory")  // WHAT was purchased
                        .transformationType(TransformationType.DIRECT)
                        .build()
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            assertThat(result.rowCount()).isEqualTo(3);

            String[] lines = result.csvContent().split("\n");
            assertThat(lines).hasSize(4); // header + 3 data rows

            // Verify header contains all expected columns
            String header = lines[0];
            assertThat(header).contains("bankCategory");
            assertThat(header).contains("paymentMethod");

            // Row 1: Decathlon - Sport category, Card payment
            String row1 = lines[1];
            assertThat(row1).contains("Decathlon Polska");  // name
            assertThat(row1).contains("Sport");              // bankCategory = semantic category
            assertThat(row1).contains("CARD");               // paymentMethod normalized from "Płatność kartą"

            // Row 2: Biedronka - Zakupy category, Card payment
            String row2 = lines[2];
            assertThat(row2).contains("Biedronka");
            assertThat(row2).contains("Zakupy");             // bankCategory = semantic category
            assertThat(row2).contains("CARD");               // paymentMethod normalized

            // Row 3: ZUS - Rachunki category, Transfer payment
            String row3 = lines[3];
            assertThat(row3).contains("ZUS");
            assertThat(row3).contains("Rachunki");           // bankCategory = semantic category
            assertThat(row3).contains("TRANSFER");           // paymentMethod normalized from "Przelew wychodzący"
        }

        @Test
        @DisplayName("Should correctly map Nest Bank CSV without Kategoria column (only paymentMethod)")
        void shouldCorrectlyMapNestBankCsv() {
            // given - Nest Bank CSV without "Kategoria" column
            // "Rodzaj operacji" = payment method like "Przelewy wychodzące"
            String csv = """
                Data księgowania,Data operacji,Rodzaj operacji,Kwota,Waluta,Dane kontrahenta,Tytuł operacji
                31-12-2025,31-12-2025,Przelewy wychodzące,-3000,PLN,Jan Kowalski,czynsz za mieszkanie
                30-12-2025,30-12-2025,Przelewy przychodzące,5000,PLN,Firma XYZ,wyplata pensji
                """;

            MappingRules rules = MappingRules.builder()
                .bankName("Nest Bank")
                .bankCountry("PL")
                .language("pl")
                .dateFormat("dd-MM-yyyy")
                .delimiter(",")
                .headerRowIndex(0)
                .columnMappings(List.of(
                    ColumnMapping.builder()
                        .sourceColumn("Data operacji")
                        .sourceIndex(1)
                        .targetField("operationDate")
                        .transformationType(TransformationType.DATE_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Dane kontrahenta")
                        .sourceIndex(5)
                        .targetField("name")
                        .transformationType(TransformationType.DIRECT)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Tytuł operacji")
                        .sourceIndex(6)
                        .targetField("description")
                        .transformationType(TransformationType.DIRECT)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(3)
                        .targetField("amount")
                        .transformationType(TransformationType.AMOUNT_PARSE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Waluta")
                        .sourceIndex(4)
                        .targetField("currency")
                        .transformationType(TransformationType.CURRENCY_EXTRACT)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Kwota")
                        .sourceIndex(3)
                        .targetField("type")
                        .transformationType(TransformationType.TYPE_DETECT)
                        .transformationParams(Map.of("amountColumn", "3"))
                        .build(),
                    // Nest Bank: "Rodzaj operacji" is payment method (not category!)
                    ColumnMapping.builder()
                        .sourceColumn("Rodzaj operacji")
                        .sourceIndex(2)
                        .targetField("paymentMethod")
                        .transformationType(TransformationType.PAYMENT_METHOD_NORMALIZE)
                        .build()
                    // Note: NO bankCategory mapping - Nest Bank doesn't have it
                ))
                .build();

            // when
            LocalCsvTransformer.TransformResult result = transformer.transform(csv, rules);

            // then
            assertThat(result.success()).isTrue();
            assertThat(result.rowCount()).isEqualTo(2);

            String[] lines = result.csvContent().split("\n");

            // Row 1: Jan Kowalski - no bankCategory, Transfer payment method
            String row1 = lines[1];
            assertThat(row1).contains("Jan Kowalski");       // name
            assertThat(row1).contains("czynsz za mieszkanie"); // description
            assertThat(row1).contains("TRANSFER");            // paymentMethod from "Przelewy wychodzące"
            assertThat(row1).contains(",OUTFLOW,");           // type from negative amount

            // Row 2: Firma XYZ - Transfer payment, INFLOW
            String row2 = lines[2];
            assertThat(row2).contains("Firma XYZ");
            assertThat(row2).contains("wyplata pensji");
            assertThat(row2).contains("TRANSFER");            // paymentMethod from "Przelewy przychodzące"
            assertThat(row2).contains(",INFLOW,");            // type from positive amount
        }

        @Test
        @DisplayName("PAYMENT_METHOD_NORMALIZE should correctly normalize Polish payment methods")
        void shouldNormalizePolishPaymentMethods() {
            // given - CSV with various Polish payment method names
            String csv = """
                Data,Kwota,Typ,Nazwa
                2023-06-15,-100.00,Płatność kartą,Test1
                2023-06-16,-200.00,BLIK,Test2
                2023-06-17,-300.00,Przelew wychodzący,Test3
                2023-06-18,-400.00,Polecenie zapłaty,Test4
                2023-06-19,-500.00,Zlecenie stałe,Test5
                2023-06-20,-600.00,Wypłata gotówkowa,Test6
                2023-06-21,-700.00,Unknown Payment Type,Test7
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
                        .sourceColumn("Typ")
                        .sourceIndex(2)
                        .targetField("paymentMethod")
                        .transformationType(TransformationType.PAYMENT_METHOD_NORMALIZE)
                        .build(),
                    ColumnMapping.builder()
                        .sourceColumn("Nazwa")
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
            String[] lines = result.csvContent().split("\n");
            assertThat(lines).hasSize(8); // header + 7 data rows

            // Verify each payment method normalization
            // paymentMethod is the LAST column, so it ends the line (no trailing comma)
            assertThat(lines[1]).endsWith(",CARD");           // "Płatność kartą" → CARD
            assertThat(lines[2]).endsWith(",BLIK");           // "BLIK" → BLIK
            assertThat(lines[3]).endsWith(",TRANSFER");       // "Przelew wychodzący" → TRANSFER
            assertThat(lines[4]).endsWith(",DIRECT_DEBIT");   // "Polecenie zapłaty" → DIRECT_DEBIT
            assertThat(lines[5]).endsWith(",STANDING_ORDER"); // "Zlecenie stałe" → STANDING_ORDER
            assertThat(lines[6]).endsWith(",CASH");           // "Wypłata gotówkowa" → CASH
            assertThat(lines[7]).endsWith(",OTHER");          // "Unknown Payment Type" → OTHER
        }
    }
}
