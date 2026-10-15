package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.exchange_connection.domain.KeyPermissionsNotReportedException;
import com.multi.vidulum.exchange_connection.domain.KeyPermissionsTooBroadException;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The non-negotiable rule from {@code OKX-CONTEXT.md}: a user's key carries {@code read_only} and
 * nothing else. OKX returns {@code perm} as a comma-separated list, so "nothing else" has to be
 * checked against every entry, not against the string as a whole — {@code "read_only,trade"}
 * starts with the right word and must still be refused.
 */
class ReportedKeyPermissionsTest {

    @Test
    void shouldAcceptReadOnlyAndKeepTheRawValue() {
        ReportedKeyPermissions permissions = ReportedKeyPermissions.of("read_only");

        assertThat(permissions.getRaw()).isEqualTo("read_only");
        assertThat(permissions.getPermissions()).containsExactly("read_only");
    }

    /**
     * Whitespace and case are normalised because the value is typed into a script by hand, but
     * the raw string is stored untouched so what was claimed stays auditable.
     */
    @Test
    void shouldNormaliseWhitespaceAndCaseWithoutLosingTheRawValue() {
        ReportedKeyPermissions permissions = ReportedKeyPermissions.of("  Read_Only  ");

        assertThat(permissions.getPermissions()).containsExactly("read_only");
        assertThat(permissions.getRaw()).isEqualTo("  Read_Only  ");
    }

    @Test
    void shouldAcceptRepeatedReadOnlyEntries() {
        assertThat(ReportedKeyPermissions.of("read_only,read_only").getPermissions())
                .containsExactly("read_only");
    }

    /**
     * The case that a naive {@code startsWith} or {@code contains} check would let through.
     */
    @Test
    void shouldRefuseKeyThatAlsoCarriesTrade() {
        assertThatThrownBy(() -> ReportedKeyPermissions.of("read_only,trade"))
                .isInstanceOf(KeyPermissionsTooBroadException.class)
                .hasMessageContaining("trade")
                .hasMessageContaining("read_only,trade");
    }

    @ParameterizedTest
    @ValueSource(strings = {"trade", "withdraw", "read_only,withdraw", "trade,read_only",
            "read_only, withdraw , trade"})
    void shouldRefuseAnythingBroaderThanReadOnly(String perm) {
        assertThatThrownBy(() -> ReportedKeyPermissions.of(perm))
                .isInstanceOf(KeyPermissionsTooBroadException.class);
    }

    @Test
    void shouldNameEveryReportedPermissionSoTheUserKnowsWhatToRemove() {
        assertThatThrownBy(() -> ReportedKeyPermissions.of("read_only,withdraw,trade"))
                .isInstanceOf(KeyPermissionsTooBroadException.class)
                .hasMessageContaining("read_only")
                .hasMessageContaining("withdraw")
                .hasMessageContaining("trade");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ",", " , , "})
    void shouldRefuseBlankOrEmptyPermissionLists(String perm) {
        assertThatThrownBy(() -> ReportedKeyPermissions.of(perm))
                .isInstanceOf(KeyPermissionsNotReportedException.class);
    }

    @Test
    void shouldRefuseNull() {
        assertThatThrownBy(() -> ReportedKeyPermissions.of(null))
                .isInstanceOf(KeyPermissionsNotReportedException.class);
    }

    /**
     * Not reported and too broad are different answers to the caller: the first is a malformed
     * request, the second a key we refuse. They must not collapse into one status.
     */
    @Test
    void shouldSeparateMissingPermissionsFromOverlyBroadOnes() {
        assertThat(new KeyPermissionsNotReportedException().getErrorCode())
                .isEqualTo(ErrorCode.EXCHANGE_KEY_PERMISSIONS_NOT_REPORTED);
        assertThat(ErrorCode.EXCHANGE_KEY_PERMISSIONS_NOT_REPORTED.getHttpStatus().value())
                .isEqualTo(400);

        assertThat(new KeyPermissionsTooBroadException("trade", java.util.Set.of("trade")).getErrorCode())
                .isEqualTo(ErrorCode.EXCHANGE_KEY_PERMISSIONS_TOO_BROAD);
        assertThat(ErrorCode.EXCHANGE_KEY_PERMISSIONS_TOO_BROAD.getHttpStatus().value())
                .isEqualTo(422);
    }

    @Test
    void shouldCompareByValue() {
        assertThat(ReportedKeyPermissions.of("read_only"))
                .isEqualTo(ReportedKeyPermissions.of("read_only"))
                .hasSameHashCodeAs(ReportedKeyPermissions.of("read_only"));
    }
}
