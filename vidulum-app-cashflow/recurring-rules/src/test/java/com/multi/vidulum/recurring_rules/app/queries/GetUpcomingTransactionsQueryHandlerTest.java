package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.UpcomingTransactionsCalculator;
import com.multi.vidulum.recurring_rules.app.dto.UpcomingTransactionsResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A handler with nothing of its own to decide, so the only thing worth asserting is that it passes
 * the question on unchanged - including the identifiers, which arrive as strings and leave as types.
 */
class GetUpcomingTransactionsQueryHandlerTest {

    private final UpcomingTransactionsCalculator calculator = mock(UpcomingTransactionsCalculator.class);
    private final GetUpcomingTransactionsQueryHandler handler =
            new GetUpcomingTransactionsQueryHandler(calculator);

    @Test
    void theQuestionTravelsUnchangedAndTheAnswerComesBackAsItIs() {
        UpcomingTransactionsResponse answer = UpcomingTransactionsResponse.builder()
                .totalCount(3)
                .totalInflow(Money.of(100, "PLN"))
                .build();
        when(calculator.upcomingFor(UserId.of("U10000001"), CashFlowId.of("CF10000001"), 30, 20))
                .thenReturn(answer);

        UpcomingTransactionsResponse response = handler.query(
                new GetUpcomingTransactionsQuery("U10000001", "CF10000001", 30, 20));

        assertThat(response).isSameAs(answer);
        verify(calculator).upcomingFor(UserId.of("U10000001"), CashFlowId.of("CF10000001"), 30, 20);
    }
}
