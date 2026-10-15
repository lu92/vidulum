package com.multi.vidulum.pnl.infrastructure;

import com.multi.vidulum.pnl.infrastructure.entities.PnlHistoryEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PnlMongoRepository extends MongoRepository<PnlHistoryEntity, String> {

    Optional<PnlHistoryEntity> findByUserId(String userId);
}
