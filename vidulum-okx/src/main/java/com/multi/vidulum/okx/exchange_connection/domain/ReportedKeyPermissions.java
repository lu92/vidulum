package com.multi.vidulum.okx.exchange_connection.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Permissions the API key reports — the {@code perm} field of
 * {@code GET /api/v5/account/config}, which OKX returns as a comma-separated list such as
 * {@code "read_only"} or {@code "read_only,trade"}.
 *
 * <p>The type exists so the rule from {@code OKX-CONTEXT.md} — a user's key carries
 * {@code read_only} and nothing else — is enforced by construction rather than remembered at
 * each call site. {@link #of(String)} refuses anything broader, so no
 * {@link ExchangeConnection} can hold a key that was allowed to trade or withdraw.
 *
 * <p><b>What this check is and is not.</b> It is a <i>consistency</i> check, not an
 * <i>authenticity</i> one. In the POC the value arrives from the same client that sends the
 * snapshot, so it catches a mistake but not a lie — a script could report {@code read_only} while
 * holding a key with {@code trade}. The {@code reported} prefix on the field says exactly that.
 * What it buys is that the rule exists as code with a test and a log entry behind it; when the
 * backend fetches {@code perm} itself, the same validation becomes a real control and only the
 * source of the input changes.
 *
 * <p>The raw string is kept alongside the parsed set so that what the client claimed stays
 * auditable, character for character.
 */
@Getter
@EqualsAndHashCode
public final class ReportedKeyPermissions {

    public static final String READ_ONLY = "read_only";

    /** Exactly what the client sent, before trimming or lowercasing. */
    private final String raw;

    private final Set<String> permissions;

    private ReportedKeyPermissions(String raw, Set<String> permissions) {
        this.raw = raw;
        this.permissions = permissions;
    }

    /**
     * @throws KeyPermissionsNotReportedException when nothing was reported
     * @throws KeyPermissionsTooBroadException when anything beyond {@code read_only} was reported
     */
    public static ReportedKeyPermissions of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new KeyPermissionsNotReportedException();
        }

        Set<String> parsed = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(entry -> entry.toLowerCase(Locale.ROOT))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        if (parsed.isEmpty()) {
            throw new KeyPermissionsNotReportedException();
        }
        if (!parsed.equals(Set.of(READ_ONLY))) {
            throw new KeyPermissionsTooBroadException(raw, parsed);
        }

        return new ReportedKeyPermissions(raw, Set.copyOf(parsed));
    }

    @Override
    public String toString() {
        return raw;
    }
}
