package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.cashflow.infrastructure.entity.CashChangeEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CashChangeMongoRepository extends MongoRepository<CashChangeEntity, String> {

    Optional<CashChangeEntity> findByCashChangeId(String cashChangeId);
}
