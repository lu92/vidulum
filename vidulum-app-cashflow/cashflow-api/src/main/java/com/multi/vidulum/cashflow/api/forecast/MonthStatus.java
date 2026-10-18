package com.multi.vidulum.cashflow.api.forecast;

/**
 * What a cash flow's forecast says about one month.
 *
 * <p>This is <b>not</b> the aggregate's lifecycle. A cash flow has three phases - SETUP, OPEN,
 * CLOSED - and they say what may be done to the cash flow as a whole; these six say what may be
 * done to a single month inside it, and they are owned by {@code cashflow_forecast_processor},
 * which builds them asynchronously from the events the aggregate emits. The two vocabularies
 * answer different questions and must not be merged.
 *
 * <p>The constants mirror {@code CashFlowMonthlyForecast.Status} one for one. The copy is
 * deliberate: a contract may not expose the read model's internal type, or a consumer could not
 * compile without the producer's implementation on its classpath. What keeps the two in step is
 * that the implementation of {@link CashFlowForecastApi} maps between them with an exhaustive
 * {@code switch}, so adding a status there fails to compile until it is named here.
 */
public enum MonthStatus {

    /**
     * Historical month waiting for import, before attestation. Created by
     * {@code createCashFlowWithHistory} for every month before the active one.
     */
    IMPORT_PENDING,

    /**
     * Historical month whose import has been attested. Still open to gap filling - a statement
     * row that was missed the first time may still arrive.
     */
    IMPORTED,

    /**
     * Month closed by rollover, which is how months close now. Open to gap filling.
     */
    ROLLED_OVER,

    /**
     * Month closed by manual attestation. <b>Read-only</b>, and the only one of the three closed
     * statuses that is: this is the status that makes staging stricter than the aggregate, which
     * checks nothing of the sort.
     *
     * @deprecated by the read model in favour of {@link #ROLLED_OVER}, and kept because existing
     *         data carries it and {@code MonthAttestedEventHandler} still writes it.
     */
    @Deprecated
    ATTESTED,

    /**
     * The current month. Exactly one month has this status, and it is where ongoing sync lands.
     */
    ACTIVE,

    /**
     * A future month holding projections. Importing into it is refused - a transaction cannot
     * have been paid in a month that has not happened.
     */
    FORECASTED
}
