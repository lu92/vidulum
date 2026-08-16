package com.multi.vidulum.cashflow.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("BankIdentifierCode (BIC/SWIFT)")
class BankIdentifierCodeTest {

    @Nested
    @DisplayName("Valid BIC acceptance")
    class ValidBicAcceptance {

        @Test
        @DisplayName("Should accept valid 8-character BIC")
        void shouldAcceptValid8CharacterBic() {
            // given
            String validBic = "DEUTDEFF";

            // when
            BankIdentifierCode bic = new BankIdentifierCode(validBic);

            // then
            assertThat(bic.value()).isEqualTo(validBic);
            assertThat(bic.extractBankCode()).isEqualTo("DEUT");
            assertThat(bic.extractCountryCode().code()).isEqualTo("DE");
            assertThat(bic.extractLocationCode()).isEqualTo("FF");
            assertThat(bic.extractBranchCode()).isNull(); // 8-char BIC has no branch
        }

        @Test
        @DisplayName("Should accept valid 11-character BIC with branch")
        void shouldAcceptValid11CharacterBic() {
            // given
            String validBic = "DEUTDEFF500";

            // when
            BankIdentifierCode bic = new BankIdentifierCode(validBic);

            // then
            assertThat(bic.value()).isEqualTo(validBic);
            assertThat(bic.extractBankCode()).isEqualTo("DEUT");
            assertThat(bic.extractCountryCode().code()).isEqualTo("DE");
            assertThat(bic.extractLocationCode()).isEqualTo("FF");
            assertThat(bic.extractBranchCode()).isEqualTo("500");
        }

        @Test
        @DisplayName("Should accept Polish BIC (PKO BP)")
        void shouldAcceptPolishBic() {
            // given
            String validBic = "BPKOPLPW";

            // when
            BankIdentifierCode bic = new BankIdentifierCode(validBic);

            // then
            assertThat(bic.extractBankCode()).isEqualTo("BPKO");
            assertThat(bic.extractCountryCode().code()).isEqualTo("PL");
            assertThat(bic.extractLocationCode()).isEqualTo("PW");
        }

        @Test
        @DisplayName("Should accept Polish BIC (ING Bank Śląski)")
        void shouldAcceptIngBic() {
            // given
            String validBic = "INGBPLPW";

            // when
            BankIdentifierCode bic = new BankIdentifierCode(validBic);

            // then
            assertThat(bic.extractBankCode()).isEqualTo("INGB");
            assertThat(bic.extractCountryCode().code()).isEqualTo("PL");
            assertThat(bic.extractLocationCode()).isEqualTo("PW");
        }

        @Test
        @DisplayName("Should accept UK BIC (HSBC)")
        void shouldAcceptUkBic() {
            // given
            String validBic = "MIDLGB22";

            // when
            BankIdentifierCode bic = new BankIdentifierCode(validBic);

            // then
            assertThat(bic.extractBankCode()).isEqualTo("MIDL");
            assertThat(bic.extractCountryCode().code()).isEqualTo("GB");
            assertThat(bic.extractLocationCode()).isEqualTo("22");
        }
    }

    @Nested
    @DisplayName("BIC normalization")
    class BicNormalization {

        @Test
        @DisplayName("Should normalize lowercase BIC to uppercase")
        void shouldNormalizeLowercaseBic() {
            // given
            String lowercaseBic = "deutdeff";

            // when
            BankIdentifierCode bic = new BankIdentifierCode(lowercaseBic);

            // then
            assertThat(bic.value()).isEqualTo("DEUTDEFF");
        }

        @Test
        @DisplayName("Should normalize BIC with spaces")
        void shouldNormalizeBicWithSpaces() {
            // given
            String bicWithSpaces = "DEUT DEFF";

            // when
            BankIdentifierCode bic = new BankIdentifierCode(bicWithSpaces);

            // then
            assertThat(bic.value()).isEqualTo("DEUTDEFF");
        }

        @Test
        @DisplayName("Should normalize mixed case BIC")
        void shouldNormalizeMixedCaseBic() {
            // given
            String mixedCaseBic = "DeUtDeFf500";

            // when
            BankIdentifierCode bic = new BankIdentifierCode(mixedCaseBic);

            // then
            assertThat(bic.value()).isEqualTo("DEUTDEFF500");
        }
    }

    @Nested
    @DisplayName("Invalid BIC rejection")
    class InvalidBicRejection {

        @Test
        @DisplayName("Should throw exception for invalid BIC format")
        void shouldThrowExceptionForInvalidBicFormat() {
            // given
            String invalidBic = "INVALID";

            // when & then
            assertThatThrownBy(() -> new BankIdentifierCode(invalidBic))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("BIC/SWIFT format")
                .extracting("accountType")
                .isEqualTo(InvalidBankAccountNumberException.AccountType.SWIFT_BIC);
        }

        @Test
        @DisplayName("Should throw exception for too short BIC")
        void shouldThrowExceptionForTooShortBic() {
            // given
            String invalidBic = "DEUT";

            // when & then
            assertThatThrownBy(() -> new BankIdentifierCode(invalidBic))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("BIC/SWIFT format");
        }

        @Test
        @DisplayName("Should throw exception for 9-character BIC (invalid length)")
        void shouldThrowExceptionFor9CharacterBic() {
            // given - BIC must be 8 or 11 characters
            String invalidBic = "DEUTDEFF5";

            // when & then
            assertThatThrownBy(() -> new BankIdentifierCode(invalidBic))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("BIC/SWIFT format");
        }

        @Test
        @DisplayName("Should throw exception for 10-character BIC (invalid length)")
        void shouldThrowExceptionFor10CharacterBic() {
            // given - BIC must be 8 or 11 characters
            String invalidBic = "DEUTDEFF50";

            // when & then
            assertThatThrownBy(() -> new BankIdentifierCode(invalidBic))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("BIC/SWIFT format");
        }

        @Test
        @DisplayName("Should throw exception for null BIC")
        void shouldThrowExceptionForNullBic() {
            // when & then
            assertThatThrownBy(() -> new BankIdentifierCode(null))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("cannot be null or blank");
        }

        @Test
        @DisplayName("Should throw exception for blank BIC")
        void shouldThrowExceptionForBlankBic() {
            // when & then
            assertThatThrownBy(() -> new BankIdentifierCode("   "))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("cannot be null or blank");
        }

        @Test
        @DisplayName("Should throw exception for empty BIC")
        void shouldThrowExceptionForEmptyBic() {
            // when & then
            assertThatThrownBy(() -> new BankIdentifierCode(""))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("cannot be null or blank");
        }

        @Test
        @DisplayName("Should throw exception for BIC with invalid characters")
        void shouldThrowExceptionForBicWithInvalidCharacters() {
            // given
            String invalidBic = "DEUT@EFF";

            // when & then
            assertThatThrownBy(() -> new BankIdentifierCode(invalidBic))
                .isInstanceOf(InvalidBankAccountNumberException.class)
                .hasMessageContaining("BIC/SWIFT format");
        }
    }
}
