package com.multi.vidulum.portfolio_spec.app.commands.resume;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * The same exchange account is back after a break — bring the portfolio up to date (task H3).
 *
 * <p>Carries a snapshot, unlike the bare {@code reconnect}, and that is the whole point. Putting a
 * connection back into service without reading the account leaves {@code ACTIVE} standing over
 * numbers from before the break: the status says the connection works and the figures are a week
 * old, with nothing in the payload to tell the two apart.
 *
 * <p>Neither the currency nor the portfolio is passed in. Both come from the connection — it
 * already knows what it is valued in and which portfolio is its own, and accepting either from the
 * caller would invite a second opinion about a settled fact (the rule C10 arrived at).
 */
public record ResumeConnectionCommand(
        UserId userId,
        ExchangeConnectionId connectionId,
        ExchangeSnapshot snapshot) implements Command {
}
