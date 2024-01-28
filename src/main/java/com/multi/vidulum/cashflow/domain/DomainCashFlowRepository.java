package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.shared.ddd.DomainRepository;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.util.List;

public interface DomainCashFlowRepository extends DomainRepository<CashFlowId, CashFlow> {
    List<DomainEvent> findDomainEvents(CashFlowId cashFlowId);

}
