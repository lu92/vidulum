package com.multi.vidulum.pnl.infrastructure;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.pnl.domain.PnlId;
import com.multi.vidulum.pnl.infrastructure.entities.PnlHistoryEntity;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@AllArgsConstructor
public class DomainPnlHistoryImpl implements DomainPnlRepository {
    private final PnlMongoRepository pnlMongoRepository;

    @Override
    public Optional<PnlHistory> findById(PnlId pnlId) {
        return pnlMongoRepository.findById(pnlId.getId())
                .map(PnlHistoryEntity::toSnapshot)
                .map(PnlHistory::from);
    }

    @Override
    public PnlHistory save(PnlHistory aggregate) {
        PnlHistoryEntity entity = PnlHistoryEntity.fromSnapshot(aggregate.getSnapshot());
        PnlHistoryEntity saved = pnlMongoRepository.save(entity);
        return PnlHistory.from(saved.toSnapshot());
    }

    @Override
    public Optional<PnlHistory> findByUser(UserId userId) {
        return pnlMongoRepository.findByUserId(userId.getId())
                .map(PnlHistoryEntity::toSnapshot)
                .map(PnlHistory::from);
    }
}
