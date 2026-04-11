package com.multi.vidulum.bank_data_adapter.app;

import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationDocument;
import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationRepository;
import com.multi.vidulum.bank_data_adapter.infrastructure.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for date range extraction logic in AiBankCsvTransformService.
 * Tests the extractDateRange method which parses transformed CSV and extracts
 * date statistics (min/max dates, months covered, suggested start period).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Date Range Extraction")
class DateRangeExtractionTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private AiPromptBuilder promptBuilder;

    @Mock
    private AiResponseProcessor responseProcessor;

    @Mock
    private AiMappingRulesPromptBuilder mappingRulesPromptBuilder;

    @Mock
    private AiMappingRulesProcessor mappingRulesProcessor;

    @Mock
    private CsvAnonymizer csvAnonymizer;

    @Mock
    private LocalCsvTransformer localCsvTransformer;

    @Mock
    private MappingRulesCacheService mappingRulesCacheService;

    @Mock
    private AiCsvTransformationRepository transformationRepository;

    @Mock
    private CsvFormatDetector csvFormatDetector;

    private AiBankCsvTransformService service;

    private static final Clock FIXED_CLOCK = Clock.fixed(
        ZonedDateTime.of(2026, 3, 22, 12, 0, 0, 0, ZoneOffset.UTC).toInstant(),
        ZoneOffset.UTC
    );

    @BeforeEach
    void setUp() {
        service = new AiBankCsvTransformService(
            chatModel,
            promptBuilder,
            responseProcessor,
            mappingRulesPromptBuilder,
            mappingRulesProcessor,
            csvAnonymizer,
            localCsvTransformer,
            mappingRulesCacheService,
            transformationRepository,
            csvFormatDetector,
            FIXED_CLOCK
        );
    }

    @Nested
    @DisplayName("extractDateRange - Valid CSV")
    class ValidCsvTests {

        @Test
        @DisplayName("Should extract date range from valid CSV with multiple months")
        void shouldExtractDateRangeFromValidCsv() {
            // given
            String csv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TX001,Salary,Monthly salary,Income,5000.00,PLN,INFLOW,2023-06-15,2023-06-15,PL123,
                TX002,Rent,Monthly rent,Bills,2000.00,PLN,OUTFLOW,2023-07-01,2023-07-01,,PL456
                TX003,Groceries,Shopping,Food,150.00,PLN,OUTFLOW,2023-08-20,2023-08-20,,PL789
                TX004,Bonus,Year end bonus,Income,1000.00,PLN,INFLOW,2024-01-10,2024-01-10,PL123,
                """;

            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(csv);

            // then
            assertThat(stats.minDate()).isEqualTo(LocalDate.of(2023, 6, 15));
            assertThat(stats.maxDate()).isEqualTo(LocalDate.of(2024, 1, 10));
            assertThat(stats.suggestedStartPeriod()).isEqualTo("2023-06");
            assertThat(stats.monthsOfData()).isEqualTo(4);
            assertThat(stats.monthsCovered()).containsExactly(
                "2023-06", "2023-07", "2023-08", "2024-01"
            );
        }

        @Test
        @DisplayName("Should extract date range from single transaction")
        void shouldExtractDateRangeFromSingleTransaction() {
            // given
            String csv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TX001,Salary,Monthly salary,Income,5000.00,PLN,INFLOW,2023-09-15,2023-09-15,PL123,
                """;

            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(csv);

            // then
            assertThat(stats.minDate()).isEqualTo(LocalDate.of(2023, 9, 15));
            assertThat(stats.maxDate()).isEqualTo(LocalDate.of(2023, 9, 15));
            assertThat(stats.suggestedStartPeriod()).isEqualTo("2023-09");
            assertThat(stats.monthsOfData()).isEqualTo(1);
            assertThat(stats.monthsCovered()).containsExactly("2023-09");
        }

        @Test
        @DisplayName("Should handle CSV with many transactions spanning multiple years")
        void shouldHandleLargeDateRange() {
            // given
            String csv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TX001,Payment,Desc,Cat,100.00,PLN,OUTFLOW,2022-01-05,2022-01-05,,
                TX002,Payment,Desc,Cat,100.00,PLN,OUTFLOW,2022-06-15,2022-06-15,,
                TX003,Payment,Desc,Cat,100.00,PLN,OUTFLOW,2023-03-20,2023-03-20,,
                TX004,Payment,Desc,Cat,100.00,PLN,OUTFLOW,2023-12-31,2023-12-31,,
                TX005,Payment,Desc,Cat,100.00,PLN,OUTFLOW,2024-02-28,2024-02-28,,
                TX006,Payment,Desc,Cat,100.00,PLN,OUTFLOW,2025-11-11,2025-11-11,,
                """;

            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(csv);

            // then
            assertThat(stats.minDate()).isEqualTo(LocalDate.of(2022, 1, 5));
            assertThat(stats.maxDate()).isEqualTo(LocalDate.of(2025, 11, 11));
            assertThat(stats.suggestedStartPeriod()).isEqualTo("2022-01");
            assertThat(stats.monthsOfData()).isEqualTo(6);
            assertThat(stats.monthsCovered()).containsExactly(
                "2022-01", "2022-06", "2023-03", "2023-12", "2024-02", "2025-11"
            );
        }

        @Test
        @DisplayName("Should handle CSV with quoted fields containing commas")
        void shouldHandleQuotedFieldsWithCommas() {
            // given
            String csv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TX001,"Salary, June",Monthly salary,Income,5000.00,PLN,INFLOW,2023-06-15,2023-06-15,PL123,
                TX002,"Rent, apartment","Monthly rent, including utilities",Bills,2000.00,PLN,OUTFLOW,2023-07-01,2023-07-01,,PL456
                """;

            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(csv);

            // then
            assertThat(stats.minDate()).isEqualTo(LocalDate.of(2023, 6, 15));
            assertThat(stats.maxDate()).isEqualTo(LocalDate.of(2023, 7, 1));
            assertThat(stats.monthsOfData()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle multiple transactions in same month")
        void shouldHandleMultipleTransactionsInSameMonth() {
            // given
            String csv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TX001,Payment1,Desc,Cat,100.00,PLN,OUTFLOW,2023-06-01,2023-06-01,,
                TX002,Payment2,Desc,Cat,100.00,PLN,OUTFLOW,2023-06-05,2023-06-05,,
                TX003,Payment3,Desc,Cat,100.00,PLN,OUTFLOW,2023-06-15,2023-06-15,,
                TX004,Payment4,Desc,Cat,100.00,PLN,OUTFLOW,2023-06-30,2023-06-30,,
                """;

            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(csv);

            // then
            assertThat(stats.minDate()).isEqualTo(LocalDate.of(2023, 6, 1));
            assertThat(stats.maxDate()).isEqualTo(LocalDate.of(2023, 6, 30));
            assertThat(stats.monthsOfData()).isEqualTo(1);
            assertThat(stats.monthsCovered()).containsExactly("2023-06");
        }
    }

    @Nested
    @DisplayName("extractDateRange - Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should return empty stats for null CSV")
        void shouldReturnEmptyForNullCsv() {
            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(null);

            // then
            assertThat(stats.minDate()).isNull();
            assertThat(stats.maxDate()).isNull();
            assertThat(stats.suggestedStartPeriod()).isNull();
            assertThat(stats.monthsOfData()).isZero();
            assertThat(stats.monthsCovered()).isEmpty();
        }

        @Test
        @DisplayName("Should return empty stats for empty CSV")
        void shouldReturnEmptyForEmptyCsv() {
            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange("");

            // then
            assertThat(stats.minDate()).isNull();
            assertThat(stats.monthsOfData()).isZero();
        }

        @Test
        @DisplayName("Should return empty stats for blank CSV")
        void shouldReturnEmptyForBlankCsv() {
            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange("   \n  \n  ");

            // then
            assertThat(stats.minDate()).isNull();
            assertThat(stats.monthsOfData()).isZero();
        }

        @Test
        @DisplayName("Should return empty stats for header-only CSV")
        void shouldReturnEmptyForHeaderOnlyCsv() {
            // given
            String csv = "bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber";

            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(csv);

            // then
            assertThat(stats.minDate()).isNull();
            assertThat(stats.monthsOfData()).isZero();
        }

        @Test
        @DisplayName("Should return empty stats when operationDate column missing")
        void shouldReturnEmptyWhenOperationDateColumnMissing() {
            // given - CSV without operationDate column
            String csv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,bookingDate,sourceAccountNumber,targetAccountNumber
                TX001,Salary,Monthly salary,Income,5000.00,PLN,INFLOW,2023-06-15,PL123,
                """;

            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(csv);

            // then
            assertThat(stats.minDate()).isNull();
            assertThat(stats.monthsOfData()).isZero();
        }

        @Test
        @DisplayName("Should skip rows with empty operationDate")
        void shouldSkipRowsWithEmptyOperationDate() {
            // given
            String csv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TX001,Salary,Monthly salary,Income,5000.00,PLN,INFLOW,2023-06-15,2023-06-15,PL123,
                TX002,Unknown,No date,Unknown,100.00,PLN,OUTFLOW,,2023-07-01,,
                TX003,Rent,Monthly rent,Bills,2000.00,PLN,OUTFLOW,2023-08-01,2023-08-01,,PL456
                """;

            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(csv);

            // then
            assertThat(stats.minDate()).isEqualTo(LocalDate.of(2023, 6, 15));
            assertThat(stats.maxDate()).isEqualTo(LocalDate.of(2023, 8, 1));
            assertThat(stats.monthsOfData()).isEqualTo(2); // Only 2 valid months
            assertThat(stats.monthsCovered()).containsExactly("2023-06", "2023-08");
        }

        @Test
        @DisplayName("Should skip rows with unparseable dates")
        void shouldSkipRowsWithUnparseableDates() {
            // given
            String csv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TX001,Salary,Monthly salary,Income,5000.00,PLN,INFLOW,2023-06-15,2023-06-15,PL123,
                TX002,Unknown,Bad date,Unknown,100.00,PLN,OUTFLOW,15-07-2023,15-07-2023,,
                TX003,Rent,Monthly rent,Bills,2000.00,PLN,OUTFLOW,2023-08-01,2023-08-01,,PL456
                """;

            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(csv);

            // then
            assertThat(stats.minDate()).isEqualTo(LocalDate.of(2023, 6, 15));
            assertThat(stats.maxDate()).isEqualTo(LocalDate.of(2023, 8, 1));
            assertThat(stats.monthsOfData()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle empty lines in CSV")
        void shouldHandleEmptyLinesInCsv() {
            // given
            String csv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber

                TX001,Salary,Monthly salary,Income,5000.00,PLN,INFLOW,2023-06-15,2023-06-15,PL123,

                TX002,Rent,Monthly rent,Bills,2000.00,PLN,OUTFLOW,2023-07-01,2023-07-01,,PL456

                """;

            // when
            AiBankCsvTransformService.DateRangeStats stats = service.extractDateRange(csv);

            // then
            assertThat(stats.minDate()).isEqualTo(LocalDate.of(2023, 6, 15));
            assertThat(stats.maxDate()).isEqualTo(LocalDate.of(2023, 7, 1));
            assertThat(stats.monthsOfData()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("DateRangeStats record")
    class DateRangeStatsTests {

        @Test
        @DisplayName("DateRangeStats.empty() should return null/zero values")
        void emptyStatsShouldHaveNullAndZeroValues() {
            // when
            AiBankCsvTransformService.DateRangeStats empty = AiBankCsvTransformService.DateRangeStats.empty();

            // then
            assertThat(empty.minDate()).isNull();
            assertThat(empty.maxDate()).isNull();
            assertThat(empty.suggestedStartPeriod()).isNull();
            assertThat(empty.monthsOfData()).isZero();
            assertThat(empty.monthsCovered()).isEmpty();
        }

        @Test
        @DisplayName("DateRangeStats should be immutable record")
        void statsShouldBeImmutableRecord() {
            // given
            var stats = new AiBankCsvTransformService.DateRangeStats(
                LocalDate.of(2023, 1, 1),
                LocalDate.of(2023, 12, 31),
                "2023-01",
                12,
                List.of("2023-01", "2023-12")
            );

            // then - record components should be accessible
            assertThat(stats.minDate()).isEqualTo(LocalDate.of(2023, 1, 1));
            assertThat(stats.maxDate()).isEqualTo(LocalDate.of(2023, 12, 31));
            assertThat(stats.suggestedStartPeriod()).isEqualTo("2023-01");
            assertThat(stats.monthsOfData()).isEqualTo(12);
            assertThat(stats.monthsCovered()).hasSize(2);
        }
    }
}
