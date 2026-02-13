package com.multi.vidulum.cashflow.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("IbanNumber")
class IbanNumberTest {

    @Nested
    @DisplayName("Valid IBAN acceptance")
    class ValidIbanAcceptance {

        @Test
        @DisplayName("Should accept valid Polish IBAN")
        void shouldAcceptValidPolishIban() {
            // given
            String validIban = "PL61109010140000071219812874";

            // when
            IbanNumber iban = new IbanNumber(validIban);

            // then
            assertThat(iban.value()).isEqualTo(validIban);
            assertThat(iban.extractCountryCode().code()).isEqualTo("PL");
            assertThat(iban.extractBankCode().code()).isEqualTo("109"); // Poland: 3-digit bank code
            assertThat(iban.extractAccountNumber()).isEqualTo("0000071219812874"); // Account number only (library behavior)
            assertThat(iban.extractBranchCode().code()).isEqualTo("0101"); // Poland: 4-digit branch code (library behavior)
        }

        @Test
        @DisplayName("Should accept valid German IBAN")
        void shouldAcceptValidGermanIban() {
            // given
            String validIban = "DE89370400440532013000";

            // when
            IbanNumber iban = new IbanNumber(validIban);

            // then
            assertThat(iban.value()).isEqualTo(validIban);
            assertThat(iban.extractCountryCode().code()).isEqualTo("DE");
            assertThat(iban.extractBankCode().code()).isEqualTo("37040044");
            assertThat(iban.extractAccountNumber()).isEqualTo("0532013000");
        }

        @Test
        @DisplayName("Should accept valid UK IBAN")
        void shouldAcceptValidUkIban() {
            // given
            String validIban = "GB29NWBK60161331926819";

            // when
            IbanNumber iban = new IbanNumber(validIban);

            // then
            assertThat(iban.value()).isEqualTo(validIban);
            assertThat(iban.extractCountryCode().code()).isEqualTo("GB");
            assertThat(iban.extractBankCode().code()).isEqualTo("NWBK");
            assertThat(iban.extractBranchCode().code()).isEqualTo("601613");
        }

        @Test
        @DisplayName("Should accept valid French IBAN")
        void shouldAcceptValidFrenchIban() {
            // given
            String validIban = "FR1420041010050500013M02606";

            // when
            IbanNumber iban = new IbanNumber(validIban);

            // then
            assertThat(iban.value()).isEqualTo(validIban);
            assertThat(iban.extractCountryCode().code()).isEqualTo("FR");
            assertThat(iban.extractBankCode().code()).isEqualTo("20041");
            assertThat(iban.extractBranchCode().code()).isEqualTo("01005");
        }
    }

    @Nested
    @DisplayName("IBAN normalization")
    class IbanNormalization {

        @Test
        @DisplayName("Should normalize IBAN with spaces")
        void shouldNormalizeIbanWithSpaces() {
            // given
            String ibanWithSpaces = "PL 61 1090 1014 0000 0712 1981 2874";

            // when
            IbanNumber iban = new IbanNumber(ibanWithSpaces);

            // then
            assertThat(iban.value()).isEqualTo("PL61109010140000071219812874");
        }

        @Test
        @DisplayName("Should normalize lowercase IBAN to uppercase")
        void shouldNormalizeLowercaseIban() {
            // given
            String lowercaseIban = "pl61109010140000071219812874";

            // when
            IbanNumber iban = new IbanNumber(lowercaseIban);

            // then
            assertThat(iban.value()).isEqualTo("PL61109010140000071219812874");
        }

        @Test
        @DisplayName("Should normalize IBAN with mixed case and spaces")
        void shouldNormalizeIbanWithMixedCaseAndSpaces() {
            // given
            String messyIban = "de 89 3704 0044 0532 0130 00";

            // when
            IbanNumber iban = new IbanNumber(messyIban);

            // then
            assertThat(iban.value()).isEqualTo("DE89370400440532013000");
        }
    }

    @Nested
    @DisplayName("IBAN formatting")
    class IbanFormatting {

        @Test
        @DisplayName("Should format IBAN with spaces")
        void shouldFormatIbanWithSpaces() {
            // given
            String rawIban = "PL61109010140000071219812874";
            IbanNumber iban = new IbanNumber(rawIban);

            // when
            String formatted = iban.toFormattedString();

            // then
            assertThat(formatted).isEqualTo("PL61 1090 1014 0000 0712 1981 2874");
        }
    }

    @Nested
    @DisplayName("Invalid IBAN rejection")
    class InvalidIbanRejection {

        @Test
        @DisplayName("Should throw exception for invalid checksum")
        void shouldThrowExceptionForInvalidChecksum() {
            // given - last digit changed to make checksum invalid
            String invalidIban = "PL61109010140000071219812875";

            // when & then
            assertThatThrownBy(() -> new IbanNumber(invalidIban))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("check digits")
                .extracting("accountType")
                .isEqualTo(InvalidBankAccountNumberException.AccountType.IBAN);
        }

        @Test
        @DisplayName("Should throw exception for invalid format")
        void shouldThrowExceptionForInvalidFormat() {
            // given
            String invalidIban = "INVALID";

            // when & then
            assertThatThrownBy(() -> new IbanNumber(invalidIban))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("Unsupported IBAN country code");
        }

        @Test
        @DisplayName("Should throw exception for too short IBAN")
        void shouldThrowExceptionForTooShortIban() {
            // given
            String invalidIban = "PL12";

            // when & then
            assertThatThrownBy(() -> new IbanNumber(invalidIban))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("format");
        }

        @Test
        @DisplayName("Should throw exception for null IBAN")
        void shouldThrowExceptionForNullIban() {
            // when & then
            assertThatThrownBy(() -> new IbanNumber(null))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("cannot be null or blank");
        }

        @Test
        @DisplayName("Should throw exception for blank IBAN")
        void shouldThrowExceptionForBlankIban() {
            // when & then
            assertThatThrownBy(() -> new IbanNumber("   "))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("cannot be null or blank");
        }

        @Test
        @DisplayName("Should throw exception for empty IBAN")
        void shouldThrowExceptionForEmptyIban() {
            // when & then
            assertThatThrownBy(() -> new IbanNumber(""))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("cannot be null or blank");
        }

        @Test
        @DisplayName("Should throw exception for unsupported country code")
        void shouldThrowExceptionForUnsupportedCountryCode() {
            // given - XX is not a valid country code
            String invalidIban = "XX6110901014000007121981287";

            // when & then
            assertThatThrownBy(() -> new IbanNumber(invalidIban))
                .isInstanceOf(InvalidBankAccountNumberException.class);
        }
    }
}
