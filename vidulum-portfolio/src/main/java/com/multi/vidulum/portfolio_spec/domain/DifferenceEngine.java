package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Computes {@code snapshot - known state} (task D1).
 *
 * <p><b>Onboarding is not a special case.</b> It is this same calculation with an empty known
 * state, which is what stops the second synchronisation from asking everything all over again.
 *
 * <p>The engine works per {@code (ticker, subName)}, the key C2 introduced. The exchange reports
 * a total and a traded part separately, so each snapshot line naturally yields up to two rows: a
 * {@code traded} position it priced, and a {@code transferred-in} one it did not. Nothing here
 * has to invent a price for a balance it does not understand.
 *
 * <p><b>Cash is the exception, and deliberately so (task C10).</b> The portfolio's own valuation
 * currency is not split: it is one position under {@link SubName#none()}, because splitting money
 * by how it arrived describes nothing anyone can act on. It also has to be that slot and no other
 * — {@code deposit} and {@code withdraw} look there, so a snapshot that filed cash under
 * {@code transferred-in} made the first deposit create a second, parallel cash position.
 *
 * <p>Only the valuation currency, not every asset at par. USDC is taken at par too, but it is not
 * what this portfolio settles in, so it stays an ordinary position and {@code none} keeps a single
 * meaning: the money this portfolio is denominated in.
 *
 * <p>Positions that did not move produce no difference at all — a synchronisation with nothing
 * to report should not create a spec (§4.5).
 */
public final class DifferenceEngine {

    private DifferenceEngine() {
    }

    /**
     * @param valuationCurrency what the portfolio is denominated in — the one ticker filed as cash
     */
    public static List<Difference> compute(
            List<Asset> knownState, ExchangeSnapshot snapshot, Currency valuationCurrency) {

        Objects.requireNonNull(valuationCurrency, "valuationCurrency is required");

        Map<PositionKey, Asset> known = knownState.stream()
                .collect(Collectors.toMap(PositionKey::of, Function.identity()));

        Map<PositionKey, Quantity> reported = new java.util.LinkedHashMap<>();
        Map<PositionKey, SnapshotPosition> sources = new java.util.LinkedHashMap<>();
        for (SnapshotPosition position : snapshot.positions()) {
            if (isCash(position.ticker(), valuationCurrency)) {
                put(reported, sources, position, SubName.none(), position.total());
            } else {
                put(reported, sources, position, SubName.traded(), position.traded());
                put(reported, sources, position, SubName.transferredIn(), position.transferredIn());
            }
        }

        List<Difference> differences = new ArrayList<>();
        for (PositionKey key : allKeys(known, reported)) {
            double held = Optional.ofNullable(known.get(key))
                    .map(asset -> asset.getQuantity().getQty()).orElse(0.0);
            double now = Optional.ofNullable(reported.get(key)).map(Quantity::getQty).orElse(0.0);
            double delta = now - held;

            if (isNegligible(delta)) {
                continue;
            }
            if (delta > 0) {
                differences.add(isCash(key.ticker(), valuationCurrency)
                        ? ResolutionRules.forCash(key.ticker(), Quantity.of(delta))
                        : ResolutionRules.forIncrease(
                                key.ticker(), key.subName(), Quantity.of(delta),
                                priceFor(sources.get(key), key.subName())));
            } else {
                differences.add(ResolutionRules.forDecrease(
                        key.ticker(), key.subName(), Quantity.of(-delta)));
            }
        }
        return List.copyOf(differences);
    }

    /**
     * Only the traded part carries a reported price. Attaching it to the transferred-in part
     * would be the original bug in a new place: pricing units the exchange never priced.
     */
    private static com.multi.vidulum.common.Price priceFor(SnapshotPosition source, SubName subName) {
        if (source == null || !SubName.traded().equals(subName)) {
            return null;
        }
        return source.reportedAvgPrice();
    }

    private static void put(Map<PositionKey, Quantity> reported,
                            Map<PositionKey, SnapshotPosition> sources,
                            SnapshotPosition position, SubName subName, Quantity quantity) {
        if (quantity.isZero()) {
            return;
        }
        PositionKey key = new PositionKey(position.ticker(), subName);
        reported.put(key, quantity);
        sources.put(key, position);
    }

    private static boolean isCash(Ticker ticker, Currency valuationCurrency) {
        return ticker.getId().equalsIgnoreCase(valuationCurrency.getId());
    }

    private static Set<PositionKey> allKeys(Map<PositionKey, Asset> known,
                                            Map<PositionKey, Quantity> reported) {
        Set<PositionKey> keys = new LinkedHashSet<>(reported.keySet());
        keys.addAll(known.keySet());
        return keys;
    }

    /**
     * {@code Quantity} is backed by a double, so subtracting equal balances lands on values like
     * {@code 1e-17}. Reporting that as a difference would make every synchronisation produce
     * noise. Removing the tolerance is part of F2.
     */
    private static boolean isNegligible(double delta) {
        return Math.abs(delta) < 1e-9;
    }

    private record PositionKey(Ticker ticker, SubName subName) {
        static PositionKey of(Asset asset) {
            return new PositionKey(asset.getTicker(), asset.getSubName());
        }
    }
}
