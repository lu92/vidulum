package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_ingestion.domain.BankCsvRow;
import com.multi.vidulum.cashflow.domain.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for CsvParserService.
 */
class CsvParserServiceTest {

    private CsvParserService csvParserService;

    @BeforeEach
    void setUp() {
        csvParserService = new CsvParserService();
    }

    @Test
    @DisplayName("Should parse valid CSV with all fields")
    void shouldParseValidCsvWithAllFields() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Netflix Subscription,Monthly streaming,Subscriptions,49.99,PLN,OUTFLOW,2021-08-15,2021-08-16,PL12345678901234567890123456,PL98765432109876543210987654
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.rows()).hasSize(1);

        BankCsvRow row = result.rows().get(0);
        assertThat(row.bankTransactionId()).isEqualTo("TXN001");
        assertThat(row.name()).isEqualTo("Netflix Subscription");
        assertThat(row.description()).isEqualTo("Monthly streaming");
        assertThat(row.bankCategory()).isEqualTo("Subscriptions");
        assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("49.99"));
        assertThat(row.currency()).isEqualTo("PLN");
        assertThat(row.type()).isEqualTo(Type.OUTFLOW);
        assertThat(row.operationDate()).isEqualTo(LocalDate.of(2021, 8, 15));
        assertThat(row.bookingDate()).isEqualTo(LocalDate.of(2021, 8, 16));
        assertThat(row.sourceAccountNumber()).isEqualTo("PL12345678901234567890123456");
        assertThat(row.targetAccountNumber()).isEqualTo("PL98765432109876543210987654");
    }

    @Test
    @DisplayName("Should parse CSV with only required fields")
    void shouldParseCsvWithOnlyRequiredFields() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                ,Groceries Shopping,,Zakupy,150.50,PLN,OUTFLOW,2021-08-15,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.rows()).hasSize(1);

        BankCsvRow row = result.rows().get(0);
        assertThat(row.bankTransactionId()).isNull();
        assertThat(row.name()).isEqualTo("Groceries Shopping");
        assertThat(row.description()).isNull();
        assertThat(row.bankCategory()).isEqualTo("Zakupy");
        assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("150.50"));
        assertThat(row.currency()).isEqualTo("PLN");
        assertThat(row.type()).isEqualTo(Type.OUTFLOW);
        assertThat(row.operationDate()).isEqualTo(LocalDate.of(2021, 8, 15));
        assertThat(row.bookingDate()).isNull();
        assertThat(row.sourceAccountNumber()).isNull();
        assertThat(row.targetAccountNumber()).isNull();
    }

    @Test
    @DisplayName("Should parse multiple rows")
    void shouldParseMultipleRows() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Netflix,Streaming,Subs,49.99,PLN,OUTFLOW,2021-08-15,,,
                TXN002,Salary,,Income,8500.00,PLN,INFLOW,2021-09-01,2021-09-01,,
                TXN003,Rent,Monthly rent,Housing,2000,PLN,OUTFLOW,2021-09-05,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.rows()).hasSize(3);
        assertThat(result.totalRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should handle European date format (dd.MM.yyyy)")
    void shouldHandleEuropeanDateFormat() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Test,Desc,Cat,100,PLN,OUTFLOW,15.08.2021,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.rows().get(0).operationDate()).isEqualTo(LocalDate.of(2021, 8, 15));
    }

    @Test
    @DisplayName("Should handle slash date formats")
    void shouldHandleSlashDateFormats() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Test1,Desc,Cat,100,PLN,OUTFLOW,15/08/2021,,,
                TXN002,Test2,Desc,Cat,100,PLN,OUTFLOW,2021/08/15,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).operationDate()).isEqualTo(LocalDate.of(2021, 8, 15));
        assertThat(result.rows().get(1).operationDate()).isEqualTo(LocalDate.of(2021, 8, 15));
    }

    @Test
    @DisplayName("Should handle comma as decimal separator")
    void shouldHandleCommaAsDecimalSeparator() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Test,Desc,Cat,"150,50",PLN,OUTFLOW,2021-08-15,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.rows().get(0).amount()).isEqualByComparingTo(new BigDecimal("150.50"));
    }

    @Test
    @DisplayName("Should handle type case insensitively")
    void shouldHandleTypeCaseInsensitively() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Out,Desc,Cat,100,PLN,outflow,2021-08-15,,,
                TXN002,In,Desc,Cat,100,PLN,INFLOW,2021-08-15,,,
                TXN003,InMixed,Desc,Cat,100,PLN,InFlow,2021-08-15,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.rows().get(0).type()).isEqualTo(Type.OUTFLOW);
        assertThat(result.rows().get(1).type()).isEqualTo(Type.INFLOW);
        assertThat(result.rows().get(2).type()).isEqualTo(Type.INFLOW);
    }

    @Test
    @DisplayName("Should report error for missing required name field")
    void shouldReportErrorForMissingRequiredName() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,,Desc,Cat,100,PLN,OUTFLOW,2021-08-15,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).rowNumber()).isEqualTo(2);
        assertThat(result.errors().get(0).message()).contains("name");
    }

    @Test
    @DisplayName("Should report error for missing required amount field")
    void shouldReportErrorForMissingRequiredAmount() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Test,Desc,Cat,,PLN,OUTFLOW,2021-08-15,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors().get(0).message()).contains("amount");
    }

    @Test
    @DisplayName("Should report error for invalid amount format")
    void shouldReportErrorForInvalidAmountFormat() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Test,Desc,Cat,not-a-number,PLN,OUTFLOW,2021-08-15,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors().get(0).message()).contains("amount");
    }

    @Test
    @DisplayName("Should report error for invalid type value")
    void shouldReportErrorForInvalidType() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Test,Desc,Cat,100,PLN,INVALID_TYPE,2021-08-15,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors().get(0).message()).contains("INFLOW or OUTFLOW");
    }

    @Test
    @DisplayName("Should report error for invalid date format")
    void shouldReportErrorForInvalidDateFormat() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Test,Desc,Cat,100,PLN,OUTFLOW,not-a-date,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors().get(0).message()).contains("date format");
    }

    @Test
    @DisplayName("Should continue parsing after row errors")
    void shouldContinueParsingAfterRowErrors() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,,Desc,Cat,100,PLN,OUTFLOW,2021-08-15,,,
                TXN002,Valid Transaction,Desc,Cat,200,PLN,INFLOW,2021-08-16,,,
                TXN003,Another,Desc,Cat,invalid-amount,PLN,OUTFLOW,2021-08-17,,,
                TXN004,Also Valid,Desc,Cat,300,PLN,OUTFLOW,2021-08-18,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.rows()).hasSize(2);
        assertThat(result.errors()).hasSize(2);
        assertThat(result.totalRows()).isEqualTo(4);

        // Verify valid rows are parsed correctly
        assertThat(result.rows().get(0).bankTransactionId()).isEqualTo("TXN002");
        assertThat(result.rows().get(1).bankTransactionId()).isEqualTo("TXN004");

        // Verify error row numbers
        assertThat(result.errors().get(0).rowNumber()).isEqualTo(2);
        assertThat(result.errors().get(1).rowNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("Should validate that amount is positive")
    void shouldValidateAmountIsPositive() {
        // given
        String csvContent = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN001,Test,Desc,Cat,-100,PLN,OUTFLOW,2021-08-15,,,
                """;

        MockMultipartFile file = createCsvFile(csvContent);

        // when
        CsvParserService.CsvParseResult result = csvParserService.parse(file);

        // then
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors().get(0).message()).contains("positive");
    }

    @Test
    @DisplayName("Should throw exception when file cannot be read")
    void shouldThrowExceptionWhenFileCannotBeRead() {
        // given
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                new byte[0]
        ) {
            @Override
            public java.io.InputStream getInputStream() throws java.io.IOException {
                throw new java.io.IOException("Cannot read file");
            }
        };

        // when/then
        assertThatThrownBy(() -> csvParserService.parse(file))
                .isInstanceOf(CsvParserService.CsvParseException.class)
                .hasMessageContaining("Failed to read CSV file");
    }

    @Test
    @DisplayName("BankCsvRow helper methods work correctly")
    void bankCsvRowHelperMethodsWorkCorrectly() {
        // given
        BankCsvRow rowWithNulls = new BankCsvRow(
                null, "Test", null, null, BigDecimal.TEN, "PLN",
                Type.OUTFLOW, LocalDate.now(), null, null, null
        );

        BankCsvRow rowWithValues = new BankCsvRow(
                "TXN1", "Test", "Desc", "Category", BigDecimal.TEN, "PLN",
                Type.OUTFLOW, LocalDate.now(), LocalDate.now().plusDays(1), "ACC1", "ACC2"
        );

        // then
        assertThat(rowWithNulls.effectiveDescription()).isEmpty();
        assertThat(rowWithNulls.effectiveBankCategory()).isEqualTo("Uncategorized");
        assertThat(rowWithNulls.effectiveBookingDate()).isEqualTo(rowWithNulls.operationDate());

        assertThat(rowWithValues.effectiveDescription()).isEqualTo("Desc");
        assertThat(rowWithValues.effectiveBankCategory()).isEqualTo("Category");
        assertThat(rowWithValues.effectiveBookingDate()).isEqualTo(rowWithValues.bookingDate());
    }

    @Test
    @DisplayName("Should handle blank bank category")
    void shouldHandleBlankBankCategory() {
        // given
        BankCsvRow row = new BankCsvRow(
                null, "Test", null, "   ", BigDecimal.TEN, "PLN",
                Type.OUTFLOW, LocalDate.now(), null, null, null
        );

        // then
        assertThat(row.effectiveBankCategory()).isEqualTo("Uncategorized");
    }

    private MockMultipartFile createCsvFile(String content) {
        return new MockMultipartFile(
                "file",
                "transactions.csv",
                "text/csv",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
