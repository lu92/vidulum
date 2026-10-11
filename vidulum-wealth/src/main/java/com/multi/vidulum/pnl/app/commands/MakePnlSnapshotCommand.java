package com.multi.vidulum.pnl.app.commands;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Range;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class MakePnlSnapshotCommand implements Command {
    UserId userId;
    Range<ZonedDateTime> dateTimeRange;

    /**
     * What to value the holdings in (task C17).
     *
     * <p>Carried by the command rather than chosen inside the handler, for the reason F7 moved
     * clocks out to the edge: a command should state the whole fact, and a snapshot's currency is
     * part of what it will record. It used to be {@code USD}, hidden two layers down in a client
     * implementation where nobody asking for a snapshot could see it, let alone disagree.
     */
    Currency valuationCurrency;

    /**
     * Written out so the builder has to pass through it: the currency is <b>required</b>, not
     * defaulted to the dollar it used to be. A default here would put the choice back where C17
     * found it — made silently, by whoever did not state one.
     */
    public MakePnlSnapshotCommand(
            UserId userId, Range<ZonedDateTime> dateTimeRange, Currency valuationCurrency) {
        if (valuationCurrency == null) {
            throw new IllegalArgumentException(
                    "a recorded valuation must say what currency it is in");
        }
        this.userId = userId;
        this.dateTimeRange = dateTimeRange;
        this.valuationCurrency = valuationCurrency;
    }
}
