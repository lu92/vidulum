package com.multi.vidulum.portfolio_spec.app.commands.cancel;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * The owner walked away from this synchronisation (task D10).
 *
 * <p>Terminal and deliberate. Without it a specification sits in {@code AWAITING_ANSWER} for
 * good, and "still thinking about it" is indistinguishable from "gone" — so anything that counts
 * pending work counts ghosts.
 */
public record CancelPortfolioSpecCommand(
        UserId userId,
        PortfolioSpecId specId) implements Command {
}
