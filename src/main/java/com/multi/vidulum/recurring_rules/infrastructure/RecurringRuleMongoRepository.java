package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.recurring_rules.domain.RuleStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecurringRuleMongoRepository extends MongoRepository<RecurringRuleEntity, String> {

    List<RecurringRuleEntity> findByCashFlowId(String cashFlowId);

    List<RecurringRuleEntity> findByUserId(String userId);

    List<RecurringRuleEntity> findByStatus(RuleStatus status);

    List<RecurringRuleEntity> findByCashFlowIdAndStatus(String cashFlowId, RuleStatus status);

    @Query("{ 'status': 'ACTIVE' }")
    List<RecurringRuleEntity> findAllActiveRules();
}
