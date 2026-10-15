package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.DomainRepository;

import java.util.List;
import java.util.Optional;

public interface DomainPnlRepository extends DomainRepository<PnlId, PnlHistory> {
    Optional<PnlHistory> findByUser(UserId userId);

    /** Everyone whose valuations are being kept — the list the daily snapshot walks (task F9). */
    List<UserId> everyOwner();
}
