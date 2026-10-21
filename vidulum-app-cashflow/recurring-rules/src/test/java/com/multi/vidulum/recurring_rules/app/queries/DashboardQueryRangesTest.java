package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDashboardParameterException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ranges the two dashboard queries carry.
 *
 * <p>They were two {@code if}s in the controller, so the limits held for requests arriving over HTTP
 * and for nobody else. On the query they are an invariant: {@code projectionMonths = 0} used to reach
 * {@code YearMonth.plusMonths(-1)} and produce a projection period ending before it starts, and the
 * only thing stopping it was an endpoint remembering to check.
 */
class DashboardQueryRangesTest {

    @Test
    void aDashboardQueryTakesBetweenOneAndNinetyDaysOfUpcoming() {
        assertThatCode(() -> new GetDashboardQuery("U1", "CF1", 1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> new GetDashboardQuery("U1", "CF1", 90, 1)).doesNotThrowAnyException();

        assertThatThrownBy(() -> new GetDashboardQuery("U1", "CF1", 0, 1))
                .isInstanceOf(InvalidDashboardParameterException.class)
                .hasMessage("upcomingDays must be between 1 and 90, but was: 0");
        assertThatThrownBy(() -> new GetDashboardQuery("U1", "CF1", 91, 1))
                .isInstanceOf(InvalidDashboardParameterException.class);
    }

    @Test
    void aDashboardQueryProjectsBetweenOneAndTwelveMonths() {
        assertThatCode(() -> new GetDashboardQuery("U1", "CF1", 7, 12)).doesNotThrowAnyException();

        assertThatThrownBy(() -> new GetDashboardQuery("U1", "CF1", 7, 0))
                .as("zero months would end the projection period before it starts")
                .isInstanceOf(InvalidDashboardParameterException.class)
                .hasMessage("projectionMonths must be between 1 and 12, but was: 0");
        assertThatThrownBy(() -> new GetDashboardQuery("U1", "CF1", 7, 13))
                .isInstanceOf(InvalidDashboardParameterException.class);
    }

    /**
     * Preserved from the endpoint: a request breaking both is answered about {@code upcomingDays},
     * and that is what the error code reports.
     */
    @Test
    void whenBothAreWrongTheFirstCheckDecidesTheMessage() {
        assertThatThrownBy(() -> new GetDashboardQuery("U1", "CF1", 0, 0))
                .isInstanceOf(InvalidDashboardParameterException.class)
                .extracting(e -> ((InvalidDashboardParameterException) e).getParameterName())
                .isEqualTo("upcomingDays");
    }

    @Test
    void anUpcomingQueryTakesBetweenOneAndNinetyDaysAndAtMostAHundredRows() {
        assertThatCode(() -> new GetUpcomingTransactionsQuery("U1", "CF1", 90, 100))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> new GetUpcomingTransactionsQuery("U1", "CF1", 0, 20))
                .isInstanceOf(InvalidDashboardParameterException.class)
                .hasMessage("days must be between 1 and 90, but was: 0");
        assertThatThrownBy(() -> new GetUpcomingTransactionsQuery("U1", "CF1", 30, 101))
                .isInstanceOf(InvalidDashboardParameterException.class)
                .hasMessage("limit must be between 1 and 100, but was: 101");
    }

    @Test
    void whenBothAreWrongOnTheUpcomingQueryDaysDecides() {
        assertThatThrownBy(() -> new GetUpcomingTransactionsQuery("U1", "CF1", 0, 0))
                .isInstanceOf(InvalidDashboardParameterException.class)
                .extracting(e -> ((InvalidDashboardParameterException) e).getParameterName())
                .isEqualTo("days");
    }

    @Test
    void theRefusalCarriesTheErrorCodeTheEndpointAnswersWith() {
        InvalidDashboardParameterException refused = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidDashboardParameterException.class,
                () -> new GetUpcomingTransactionsQuery("U1", "CF1", 30, 0));

        assertThat(refused.getErrorCode())
                .as("a BusinessException with an ErrorCode is what keeps this a 400 rather than a 500")
                .isEqualTo(com.multi.vidulum.common.error.ErrorCode.RECURRING_RULE_INVALID_PARAMETER);
    }
}
