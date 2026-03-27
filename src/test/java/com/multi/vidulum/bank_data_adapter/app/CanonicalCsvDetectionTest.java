package com.multi.vidulum.bank_data_adapter.app;

import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationDocument;
import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationRepository;
import com.multi.vidulum.bank_data_adapter.domain.DetectionResult;
import com.multi.vidulum.bank_data_adapter.infrastructure.AiMappingRulesProcessor;
import com.multi.vidulum.bank_data_adapter.infrastructure.AiMappingRulesPromptBuilder;
import com.multi.vidulum.bank_data_adapter.infrastructure.AiPromptBuilder;
import com.multi.vidulum.bank_data_adapter.infrastructure.AiResponseProcessor;
import com.multi.vidulum.bank_data_adapter.infrastructure.CsvAnonymizer;
import com.multi.vidulum.bank_data_adapter.infrastructure.LocalCsvTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for canonical CSV format detection.
 * Verifies that isCanonicalFormat() correctly identifies Vidulum format CSVs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Canonical CSV Detection")
class CanonicalCsvDetectionTest {

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

    private AiBankCsvTransformService service;

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC
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
            FIXED_CLOCK
        );
        // Set @Value fields via reflection since they're not injected in unit tests
        ReflectionTestUtils.setField(service, "maxFileSizeBytes", 5242880L); // 5MB
        ReflectionTestUtils.setField(service, "maxRetries", 2);
        ReflectionTestUtils.setField(service, "sampleRows", 10);
        ReflectionTestUtils.setField(service, "useCache", true);
    }

    @Nested
    @DisplayName("isCanonicalFormat()")
    class IsCanonicalFormatTests {

        @Test
        @DisplayName("Should detect full canonical format with all 11 headers")
        void shouldDetectFullCanonicalFormat() {
            // given
            String csv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Salary,Monthly salary,Income,5000.00,PLN,INFLOW,2023-01-15,2023-01-15,PL123,PL456
                """;

            // when
            boolean result = service.isCanonicalFormat(csv);

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should detect minimal canonical format with only required headers")
        void shouldDetectMinimalCanonicalFormat() {
            // given - only required headers: name, amount, currency, type, operationDate
            String csv = """
                name,amount,currency,type,operationDate,bankCategory,description
                Salary,5000.00,PLN,INFLOW,2023-01-15,Income,Monthly salary
                """;

            // when
            boolean result = service.isCanonicalFormat(csv);

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should not detect bank-specific format as canonical")
        void shouldNotDetectBankFormatAsCanonical() {
            // given - Nest Bank format
            String csv = """
                Data operacji,Data waluty,Typ operacji,Nadawca/Odbiorca,Tytułem,Kwota,Waluta
                2023-01-15,2023-01-15,Przelew przychodzący,Jan Kowalski,Wynagrodzenie,5000.00,PLN
                """;

            // when
            boolean result = service.isCanonicalFormat(csv);

            // then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should not detect ING format as canonical")
        void shouldNotDetectIngFormatAsCanonical() {
            // given - ING format
            String csv = """
                "Data transakcji";"Data ksiegowania";"Dane kontrahenta";"Tytul";"Nr rachunku";"Nazwa banku";"Szczegoly";"Nr transakcji";"Kwota transakcji (waluta rachunku)";"Waluta";"Kwota blokady/zwolnienie blokady";"Saldo po transakcji"
                2023-01-15;2023-01-15;Jan Kowalski;Wynagrodzenie;PL123;Bank;Details;TXN001;5000.00;PLN;0.00;10000.00
                """;

            // when
            boolean result = service.isCanonicalFormat(csv);

            // then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should not detect mBank format as canonical")
        void shouldNotDetectMBankFormatAsCanonical() {
            // given - mBank format
            String csv = """
                #Data operacji;#Opis operacji;#Rachunek;#Kategoria;#Kwota;#Saldo po operacji;
                2023-01-15;Przelew przychodzący;PL123;Przychody;5000.00;10000.00;
                """;

            // when
            boolean result = service.isCanonicalFormat(csv);

            // then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return false for empty content")
        void shouldReturnFalseForEmptyContent() {
            // when
            boolean result = service.isCanonicalFormat("");

            // then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return false for null content")
        void shouldReturnFalseForNullContent() {
            // when
            boolean result = service.isCanonicalFormat(null);

            // then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should detect canonical format with different column order")
        void shouldDetectCanonicalFormatWithDifferentOrder() {
            // given - reordered columns
            String csv = """
                operationDate,type,amount,currency,name,bankCategory,description
                2023-01-15,INFLOW,5000.00,PLN,Salary,Income,Monthly salary
                """;

            // when
            boolean result = service.isCanonicalFormat(csv);

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should handle case-insensitive header matching")
        void shouldHandleCaseInsensitiveHeaders() {
            // given - mixed case headers
            String csv = """
                NAME,Amount,Currency,TYPE,OperationDate,BankCategory,Description
                Salary,5000.00,PLN,INFLOW,2023-01-15,Income,Monthly salary
                """;

            // when
            boolean result = service.isCanonicalFormat(csv);

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should not detect format missing required headers")
        void shouldNotDetectFormatMissingRequiredHeaders() {
            // given - missing 'amount' and 'type'
            String csv = """
                name,currency,operationDate,bankCategory,description
                Salary,PLN,2023-01-15,Income,Monthly salary
                """;

            // when
            boolean result = service.isCanonicalFormat(csv);

            // then
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("transform() with canonical format")
    class TransformCanonicalFormatTests {

        @Test
        @DisplayName("Should process canonical format without AI call")
        void shouldProcessCanonicalFormatWithoutAiCall() {
            // given
            String canonicalCsv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Salary,Monthly salary,Income,5000.00,PLN,INFLOW,2023-01-15,2023-01-15,PL123,PL456
                TXN002,Rent,Monthly rent,Housing,-1500.00,PLN,OUTFLOW,2023-01-20,2023-01-20,PL123,PL789
                """;

            when(transformationRepository.findByOriginalFileHashAndUserId(any(), any()))
                .thenReturn(Optional.empty());
            when(transformationRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

            // when
            AiCsvTransformationDocument result = service.transform(
                canonicalCsv.getBytes(),
                "canonical.csv",
                null,
                "user123"
            );

            // then
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getDetectionResult()).isEqualTo(DetectionResult.CANONICAL);
            assertThat(result.isFromCache()).isFalse();
            assertThat(result.getDetectedBank()).isEqualTo("Vidulum Format");
            assertThat(result.getOutputRowCount()).isEqualTo(2);
            assertThat(result.getTransformedCsvContent()).isEqualTo(canonicalCsv);
            assertThat(result.getProcessingTimeMs()).isLessThan(1000); // Should be instant
        }

        @Test
        @DisplayName("Should extract date range from canonical format")
        void shouldExtractDateRangeFromCanonicalFormat() {
            // given
            String canonicalCsv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Salary,Monthly salary,Income,5000.00,PLN,INFLOW,2023-01-15,2023-01-15,PL123,PL456
                TXN002,Rent,Monthly rent,Housing,-1500.00,PLN,OUTFLOW,2023-03-20,2023-03-20,PL123,PL789
                TXN003,Food,Groceries,Food,-200.00,PLN,OUTFLOW,2023-02-10,2023-02-10,PL123,PL999
                """;

            when(transformationRepository.findByOriginalFileHashAndUserId(any(), any()))
                .thenReturn(Optional.empty());
            when(transformationRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

            // when
            AiCsvTransformationDocument result = service.transform(
                canonicalCsv.getBytes(),
                "canonical.csv",
                null,
                "user123"
            );

            // then
            assertThat(result.getMinTransactionDate()).hasToString("2023-01-15");
            assertThat(result.getMaxTransactionDate()).hasToString("2023-03-20");
            assertThat(result.getSuggestedStartPeriod()).isEqualTo("2023-01");
            assertThat(result.getMonthsOfData()).isEqualTo(3);
            assertThat(result.getMonthsCovered()).containsExactly("2023-01", "2023-02", "2023-03");
        }
    }
}
