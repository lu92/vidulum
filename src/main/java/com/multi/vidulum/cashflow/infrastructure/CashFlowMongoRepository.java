package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.cashflow.infrastructure.entity.CashFlowEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CashFlowMongoRepository extends MongoRepository<CashFlowEntity, String> {

    Optional<CashFlowEntity> findByCashFlowId(String cashFlowId);
}
