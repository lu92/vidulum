package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Ticker;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the exchange said at a point in time — the anchor every answer is validated against.
 *
 * <p>In the POC it arrives in the request body, because the backend holds no credentials and
 * cannot fetch it itself. That makes it a consistency check rather than an authenticity one, the
 * same distinction as {@code reportedKeyPermissions}: it catches a mistake, not a lie.
 */
public record ExchangeSnapshot(
        Broker broker,
        ZonedDateTime takenAt,
        List<SnapshotPosition> positions) {

    public ExchangeSnapshot {
        Objects.requireNonNull(broker, "broker is required");
        Objects.requireNonNull(takenAt, "takenAt is required");
        Objects.requireNonNull(positions, "positions are required");
        long distinct = positions.stream().map(SnapshotPosition::ticker).distinct().count();
        if (distinct != positions.size()) {
            throw new IllegalArgumentException("snapshot lists the same ticker more than once");
        }
        positions = List.copyOf(positions);
    }

    public Optional<SnapshotPosition> find(Ticker ticker) {
        return positions.stream().filter(p -> p.ticker().equals(ticker)).findFirst();
    }
}
