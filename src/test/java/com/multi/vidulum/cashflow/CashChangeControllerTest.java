package com.multi.vidulum.cashflow;

import com.multi.vidulum.cashflow.app.CashChangeDto;
import com.multi.vidulum.cashflow.app.CashChangeRestController;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZonedDateTime;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.CONFIRMED;
import static com.multi.vidulum.cashflow.domain.CashChangeStatus.PENDING;
import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static org.assertj.core.api.Assertions.assertThat;

public class CashChangeControllerTest extends IntegrationTest {

    @Autowired
    private CashChangeRestController cashChangeRestController;

    @Test
    void shouldCreateAndGetCashChange() {

        // when
        CashChangeDto.CashChangeSummaryJson summary = cashChangeRestController.create(
                CashChangeDto.CreateEmptyCashChangeJson.builder()
                        .userId("userId")
                        .name("name")
                        .description("description")
                        .money(Money.of(100, "USD"))
                        .type(Type.INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );
        CashChangeDto.CashChangeSummaryJson cashChangeSummaryJson = cashChangeRestController.getCashChange(summary.getCashChangeId());

        // then
        assertThat(summary).isNotNull();
        assertThat(cashChangeSummaryJson).isEqualTo(
                CashChangeDto.CashChangeSummaryJson.builder()
                        .cashChangeId(summary.getCashChangeId())
                        .userId("userId")
                        .name("name")
                        .description("description")
                        .type(INFLOW)
                        .status(PENDING)
                        .created(ZonedDateTime.parse("2022-01-01T00:00:00Z"))
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );
    }

    @Test
    void shouldConfirmCashChange() {
        CashChangeDto.CashChangeSummaryJson summary = cashChangeRestController.create(
                CashChangeDto.CreateEmptyCashChangeJson.builder()
                        .userId("userId")
                        .name("name")
                        .description("description")
                        .money(Money.of(100, "USD"))
                        .type(Type.INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );

        cashChangeRestController.confirm(
                CashChangeDto.ConfirmCashChangeJson.builder()
                        .cashChangeId(summary.getCashChangeId())
                        .build());

        assertThat(cashChangeRestController.getCashChange(summary.getCashChangeId()))
                .matches(cashChangeSummaryJson -> CONFIRMED.equals(cashChangeSummaryJson.getStatus()));
    }

    @Test
    void shouldEditCashChange() {
        CashChangeDto.CashChangeSummaryJson summary = cashChangeRestController.create(
                CashChangeDto.CreateEmptyCashChangeJson.builder()
                        .userId("userId")
                        .name("name")
                        .description("description")
                        .money(Money.of(100, "USD"))
                        .type(Type.INFLOW)
                        .dueDate(ZonedDateTime.parse("2024-01-10T00:00:00Z"))
                        .build()
        );

        cashChangeRestController.edit(
                CashChangeDto.EditCashChangeJson.builder()
                        .cashChangeId(summary.getCashChangeId())
                        .name("name edited")
                        .description("description edited")
                        .money(Money.of(200, "USD"))
                        .dueDate(ZonedDateTime.parse("2024-02-10T00:00:00Z"))
                        .build()
        );

        assertThat(domainCashChangeRepository.findById(new CashChangeId(summary.getCashChangeId())))
                .isPresent()
                .map(CashChange::getSnapshot)
                .get()
                .isEqualTo(new CashChangeSnapshot(
                                new CashChangeId(summary.getCashChangeId()),
                                UserId.of("userId"),
                                new Name("name edited"),
                                new Description("description edited"),
                                Money.of(200, "USD"),
                                INFLOW,
                                PENDING,
                                ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                                ZonedDateTime.parse("2024-02-10T00:00:00Z"),
                                null
                        )
                );
    }
}
