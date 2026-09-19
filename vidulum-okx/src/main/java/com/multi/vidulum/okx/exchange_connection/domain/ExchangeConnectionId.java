package com.multi.vidulum.okx.exchange_connection.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Surrogate identifier of a connection. Mirrors {@code PortfolioId} — a UUID, not a sequence,
 * because unlike {@code CashFlowId} it is never shown to the user or typed by hand.
 *
 * <p>The identifier a human would recognise is {@code accountUid} from the exchange; see
 * {@link ExchangeConnection} for why that one is a natural key rather than the primary key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeConnectionId {

    String id;

    public static ExchangeConnectionId of(String id) {
        return new ExchangeConnectionId(id);
    }

    public static ExchangeConnectionId generate() {
        return ExchangeConnectionId.of(UUID.randomUUID().toString());
    }
}
