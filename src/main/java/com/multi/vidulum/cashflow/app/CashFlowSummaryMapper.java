package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CashFlowSummaryMapper {

    public CashFlowDto.CashFlowSummaryJson mapCashChange(CashFlowSnapshot cashFlowSnapshot) {
        Map<String, CashFlowDto.CashChangeSummaryJson> cashChanges = cashFlowSnapshot.cashChanges().values()
                .stream()
                .map(this::mapCashChange)
                .collect(Collectors.toUnmodifiableMap(
                        CashFlowDto.CashChangeSummaryJson::getCashChangeId,
                        Function.identity()
                ));

        return CashFlowDto.CashFlowSummaryJson.builder()
                .cashFlowId(cashFlowSnapshot.cashFlowId().id())
                .userId(cashFlowSnapshot.userId().getId())
                .name(cashFlowSnapshot.name().name())
                .description(cashFlowSnapshot.description().description())
                .bankAccount(cashFlowSnapshot.bankAccount())
                .status(cashFlowSnapshot.status())
                .cashChanges(cashChanges)
                .created(cashFlowSnapshot.created())
                .lastModification(cashFlowSnapshot.lastModification())
                .build();
    }

    private CashFlowDto.CashChangeSummaryJson mapCashChange(CashChangeSnapshot snapshot) {
        return CashFlowDto.CashChangeSummaryJson.builder()
                .cashChangeId(snapshot.cashChangeId().id())
                .name(snapshot.name().name())
                .description(snapshot.description().description())
                .money(snapshot.money())
                .type(snapshot.type())
                .status(snapshot.status())
                .created(snapshot.created())
                .dueDate(snapshot.dueDate())
                .endDate(snapshot.endDate())
                .build();
    }
}
