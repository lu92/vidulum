package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.shared.ddd.DomainRepository;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.util.List;

public interface DomainCashChangeRepository extends DomainRepository<CashChangeId, CashChange> {
    List<DomainEvent> findDomainEvents(CashChangeId cashChangeId);

}
