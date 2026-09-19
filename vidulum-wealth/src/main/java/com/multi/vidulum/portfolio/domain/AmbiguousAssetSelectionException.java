package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;

import java.util.List;

/**
 * The ticker is held in more than one position and the caller did not say which one it meant.
 *
 * <p>Raised instead of picking the first match. A portfolio whose {@code locked} quantities stop
 * matching reality is worse than a rejected request — and picking by list order is exactly how
 * that happens: the lock lands on {@code transferred-in} while the unlock looks for it on
 * {@code traded}.
 */
public class AmbiguousAssetSelectionException extends RuntimeException {

    public AmbiguousAssetSelectionException(Ticker ticker, List<SubName> candidates) {
        super(String.format("Asset [%s] is held in %d positions %s - say which one", 
                ticker.getId(),
                candidates.size(),
                candidates.stream().map(SubName::getName).toList()));
    }
}
