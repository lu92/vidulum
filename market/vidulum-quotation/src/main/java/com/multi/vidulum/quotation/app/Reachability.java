package com.multi.vidulum.quotation.app;

/**
 * Whether the exchange itself answers.
 *
 * <p>Everything is {@link #UNKNOWN} today, and deliberately so: nothing in the backend calls an
 * exchange. Reporting {@code ONLINE} because our own caches happen to be warm would answer a
 * different question from the one the field asks, and would be believed.
 *
 * <p>The probe belongs to each exchange's own module — it knows its hosts and its rate limits —
 * so it will arrive as a method on {@code ExchangeAdapter}. Until then this enum exists to keep
 * the distinction visible rather than to pretend it is resolved.
 */
public enum Reachability {
    ONLINE,
    DEGRADED,
    OFFLINE,
    UNKNOWN
}
