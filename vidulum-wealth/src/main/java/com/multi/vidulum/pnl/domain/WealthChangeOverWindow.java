package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;

/**
 * How much the owner's wealth changed between two moments (task C14).
 *
 * <p>C5 answers the same question since the portfolio began, because that was the only starting
 * point available without stored valuations. This is the question people actually ask second — "and
 * this month?" — and the formula is the one C5 uses, with both ends dated:
 *
 * <pre>value(now) - value(then) - (contributed(now) - contributed(then))</pre>
 *
 * <p>The subtracted term is what keeps a deposit from reading as growth, exactly as in C5. It needs
 * no new data: a recorded valuation carries what had been put in by then (F9), so the contributions
 * inside the window are the difference between the two figures rather than a walk over the ledger.
 *
 * @param since     the moment asked about
 * @param measuredFrom when the valuation actually used was taken — a daily record rarely lands on
 *                     the requested instant, and a reader is entitled to know which day answered
 * @param status    why a figure is present or absent; never inferred from {@code null}
 */
public record WealthChangeOverWindow(
        ZonedDateTime since,
        ZonedDateTime measuredFrom,
        Money change,
        Double pct,
        WealthChangeStatus status) {

    public static WealthChangeOverWindow absent(ZonedDateTime since, WealthChangeStatus status) {
        return new WealthChangeOverWindow(since, null, null, null, status);
    }

    /** Why a windowed change is or is not being reported (task C14). */
    public enum WealthChangeStatus {

        /** Both ends were known and the figure is stated. */
        COMPUTED,

        /**
         * Nothing was recorded that early. The window starts before we began watching, and the
         * only way to answer would be to value the old holdings at today's prices — which is the
         * untruth this whole measure exists to avoid.
         */
        NOT_WATCHING_YET,

        /**
         * One end could not say what had been put in, so the deposits inside the window cannot be
         * netted out — and an unnetted figure reports a transfer as profit. Same rule as C5, and
         * the reason it is a status rather than a {@code null} to be guessed at.
         */
        CONTRIBUTIONS_UNKNOWN
    }
}
