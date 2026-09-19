package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;

/**
 * A portfolio may hold a ticker in several positions, but never twice under the same
 * {@code subName}. Every other C2 rule — which position a lock lands on, which one a trade feeds,
 * which ones a view sums — assumes that key identifies exactly one row.
 */
public class DuplicateAssetPositionException extends RuntimeException {

    public DuplicateAssetPositionException(Ticker ticker, SubName subName) {
        super(String.format("Asset [%s] already held under position [%s]",
                ticker.getId(), subName.getName()));
    }
}
