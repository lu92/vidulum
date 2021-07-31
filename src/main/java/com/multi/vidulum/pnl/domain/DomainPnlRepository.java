package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.DomainRepository;

import java.util.Optional;

public interface DomainPnlRepository extends DomainRepository<PnlId, PnlHistory> {
    Optional<PnlHistory> findByUser(UserId userId);
}
