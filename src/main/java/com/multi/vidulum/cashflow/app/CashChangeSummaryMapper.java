package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import org.springframework.stereotype.Component;

@Component
public class CashChangeSummaryMapper {

    CashChangeDto.CashChangeSummaryJson map(CashChangeSnapshot snapshot) {
        return CashChangeDto.CashChangeSummaryJson.builder()
                .cashChangeId(snapshot.cashChangeId().id())
                .userId(snapshot.userId().getId())
                .name(snapshot.name().name())
                .description(snapshot.description().description())
                .type(snapshot.type())
                .status(snapshot.status())
                .created(snapshot.created())
                .dueDate(snapshot.dueDate())
                .build();
    }
}
