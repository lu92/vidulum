package com.multi.vidulum.portfolio.domain.portfolio;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Identity of one entry in a portfolio's contribution ledger.
 *
 * <p>Mirrors {@code PortfolioId} and {@link com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId}:
 * a UUID, never shown to the user or typed by hand. A bare {@code String} said nothing about what
 * it identified, and the deposit path passes an id, a portfolio id and a currency code side by
 * side — three strings the compiler was happy to see swapped.
 *
 * <p>Lives beside {@link Contribution} rather than in the shared kernel, because a contribution is
 * not an aggregate anybody else addresses; only this module ever names one.
 *
 * <p>It exists at all because backfill (C13) has to <b>replace</b> the opening entry C12 writes,
 * and you cannot replace what you cannot point at.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContributionId {

    String id;

    public static ContributionId of(String id) {
        return new ContributionId(id);
    }

    public static ContributionId generate() {
        return ContributionId.of(UUID.randomUUID().toString());
    }
}
