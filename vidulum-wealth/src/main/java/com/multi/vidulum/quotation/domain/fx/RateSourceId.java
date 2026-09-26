package com.multi.vidulum.quotation.domain.fx;

/**
 * Who published a rate. Not a {@code Broker}: nobody keeps assets at the central bank, and a
 * portfolio cannot be opened there. Keeping the two names apart stops the exchange status endpoint
 * from advertising a rate publisher as somewhere you could connect an account.
 */
public record RateSourceId(String id) {

    public RateSourceId {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A rate source must be named");
        }
    }

    public static RateSourceId of(String id) {
        return new RateSourceId(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
