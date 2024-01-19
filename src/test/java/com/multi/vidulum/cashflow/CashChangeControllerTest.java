package com.multi.vidulum.cashflow;

import com.multi.vidulum.cashflow.app.CashChangeDto;
import com.multi.vidulum.cashflow.app.CashChangeRestController;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

public class CashChangeControllerTest extends IntegrationTest {

    @Autowired
    private CashChangeRestController cashChangeRestController;

    @Test
    void shouldCreateCashChange() {

        CashChangeDto.CashChangeSummaryJson summary = cashChangeRestController.createEmptyCashChange(
                CashChangeDto.CreateEmptyCashChangeJson.builder()
                        .userId("userId")
                        .name("name")
                        .description("description")
                        .money(Money.of(100, "USD"))
                        .type(Type.INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );

        assertThat(summary).isNotNull();
    }
}
