package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.recurring_rules.domain.RuleStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RecurringRuleMongoRepository extends MongoRepository<RecurringRuleEntity, String> {

    List<RecurringRuleEntity> findByCashFlowId(String cashFlowId);

    List<RecurringRuleEntity> findByUserId(String userId);

    List<RecurringRuleEntity> findByStatus(RuleStatus status);

    List<RecurringRuleEntity> findByCashFlowIdAndStatus(String cashFlowId, RuleStatus status);

    @Query("{ 'status': 'ACTIVE' }")
    List<RecurringRuleEntity> findAllActiveRules();

    /**
     * Finds paused rules that have a resumeDate on or before the given date.
     * Used by auto-resume scheduler to find rules that should be automatically resumed.
     */
    @Query("{ 'status': 'PAUSED', 'pauseInfo.resumeDate': { $ne: null, $lte: ?0 } }")
    List<RecurringRuleEntity> findPausedRulesWithResumeDateOnOrBefore(LocalDate date);

    /**
     * Count rules by user and status for dashboard.
     */
    long countByUserIdAndStatus(String userId, RuleStatus status);

    /**
     * Find rules by user and status.
     */
    List<RecurringRuleEntity> findByUserIdAndStatus(String userId, RuleStatus status);

    /**
     * Count rules by user, cashflow and status for dashboard.
     */
    long countByUserIdAndCashFlowIdAndStatus(String userId, String cashFlowId, RuleStatus status);

    /**
     * Find rules by user, cashflow and status.
     */
    List<RecurringRuleEntity> findByUserIdAndCashFlowIdAndStatus(String userId, String cashFlowId, RuleStatus status);
}
