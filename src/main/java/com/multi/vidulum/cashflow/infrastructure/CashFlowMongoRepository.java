package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.infrastructure.entity.CashFlowEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CashFlowMongoRepository extends MongoRepository<CashFlowEntity, String> {

    Optional<CashFlowEntity> findByCashFlowId(String cashFlowId);

    List<CashFlowEntity> findByUserId(String userId);

    /**
     * Finds all CashFlows in OPEN status that need rollover.
     * A CashFlow needs rollover when its activePeriod is before the given period.
     *
     * @param status       the status to filter by (should be OPEN)
     * @param targetPeriod the target period (format: yyyy-MM) - CashFlows with activePeriod before this need rollover
     * @return list of CashFlow entities needing rollover
     */
    @Query("{ 'status': ?0, 'activePeriod': { $lt: ?1 } }")
    List<CashFlowEntity> findByStatusAndActivePeriodBefore(CashFlow.CashFlowStatus status, String targetPeriod);

    /**
     * Checks if a CashFlow with the given name already exists for the specified user.
     *
     * @param userId the user ID
     * @param name the CashFlow name
     * @return true if a CashFlow with this name exists for this user, false otherwise
     */
    boolean existsByUserIdAndName(String userId, String name);
}
