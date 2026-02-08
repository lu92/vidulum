package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.DomainRepository;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.time.YearMonth;
import java.util.List;

public interface DomainCashFlowRepository extends DomainRepository<CashFlowId, CashFlow> {
    List<DomainEvent> findDomainEvents(CashFlowId cashFlowId);

    List<CashFlow> findDetailsByUserId(UserId userId);

    /**
     * Finds all CashFlows in OPEN status that need rollover.
     * A CashFlow needs rollover when its activePeriod is before the given target period.
     *
     * @param targetPeriod the target period - CashFlows with activePeriod before this need rollover
     * @return list of CashFlows needing rollover
     */
    List<CashFlow> findOpenCashFlowsNeedingRollover(YearMonth targetPeriod);
}
