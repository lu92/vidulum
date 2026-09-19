package com.multi.vidulum.exchange_connection.domain;

/**
 * Lifecycle of a connection. <b>None of these states is terminal</b> — losing contact with the
 * exchange is an interruption, not an ending, so {@link #REVOKED} keeps both {@code accountUid}
 * and {@code portfolioId} and can go back to {@link #ACTIVE}.
 *
 * <pre>
 *   PENDING  --confirm-->  ACTIVE  --revoke-->  REVOKED
 *      |  ^                  |  ^                  |
 *      |  |                  |  +---- sync --------+  (reconnect, same accountUid)
 *      v  |                  v
 *    ERROR-+               ACTIVE  (repeated synchronisation)
 * </pre>
 */
public enum ConnectionStatus {

    /** Created, not yet confirmed — no portfolio attached. */
    PENDING,

    /** Confirmed and synchronising; {@code portfolioId} is set. */
    ACTIVE,

    /** Rejected or failed; the reason is carried in {@code statusReason}. Recoverable. */
    ERROR,

    /** Key expired or the user disconnected. The portfolio stays untouched. Recoverable. */
    REVOKED
}
