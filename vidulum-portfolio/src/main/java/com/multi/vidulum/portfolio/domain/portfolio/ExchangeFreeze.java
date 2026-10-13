package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;

import java.util.Comparator;
import java.util.List;

/**
 * Spreads what an exchange has frozen over the positions we hold of that ticker (task D5).
 *
 * <p>The exchange freezes a <b>currency</b>; we keep positions split by where they came from (C2).
 * Nothing connects the two, because units are fungible — the exchange cannot say which bitcoin an
 * open order committed, and there is no fact of the matter. So the split follows a rule rather
 * than being invented per case: the traded part absorbs the freeze first, the rest spills over.
 * Traded first because an open order is the usual cause and it is placed against what was traded.
 *
 * <p>What is exact either way is the total — the sum over a ticker equals what the exchange
 * reported — and that is the number an owner acts on: how much of this can I move.
 *
 * <p>Lives here rather than in the command handler because both paths need it: onboarding, and
 * every later synchronisation (D13). One rule, one place.
 */
public final class ExchangeFreeze {

    private ExchangeFreeze() {
    }

    /**
     * @param held   every position of one ticker
     * @param frozen what the exchange says is committed across all of them
     */
    public static void spread(List<Asset> held, Quantity frozen) {
        double remaining = frozen == null ? 0 : frozen.getQty();
        for (Asset asset : tradedFirst(held)) {
            double share = Math.min(Math.max(remaining, 0), asset.getQuantity().getQty());
            asset.setLocked(Quantity.of(share, asset.getQuantity().getUnit()));
            asset.setFree(asset.getQuantity().minus(asset.getLocked()));
            remaining -= share;
        }
    }

    private static List<Asset> tradedFirst(List<Asset> assets) {
        return assets.stream()
                .sorted(Comparator.comparing(
                        asset -> SubName.traded().equals(asset.getSubName()) ? 0 : 1))
                .toList();
    }
}
