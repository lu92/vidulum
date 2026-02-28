package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.FindAndModifyOptions.options;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Component
@RequiredArgsConstructor
public class RecurringRuleRepositoryAdapter implements DomainRecurringRuleRepository {

    private final RecurringRuleMongoRepository mongoRepository;
    private final MongoTemplate mongoTemplate;

    private static final String SEQUENCE_COLLECTION = "recurring_rule_sequence";

    @Override
    public void save(RecurringRule rule) {
        RecurringRuleEntity entity = RecurringRuleEntity.fromSnapshot(rule.getSnapshot());
        mongoRepository.save(entity);
    }

    @Override
    public Optional<RecurringRule> findById(RecurringRuleId ruleId) {
        return mongoRepository.findById(ruleId.id())
                .map(RecurringRuleEntity::toSnapshot)
                .map(RecurringRule::fromSnapshot);
    }

    @Override
    public List<RecurringRule> findByCashFlowId(CashFlowId cashFlowId) {
        return mongoRepository.findByCashFlowId(cashFlowId.id()).stream()
                .map(RecurringRuleEntity::toSnapshot)
                .map(RecurringRule::fromSnapshot)
                .collect(Collectors.toList());
    }

    @Override
    public List<RecurringRule> findByUserId(UserId userId) {
        return mongoRepository.findByUserId(userId.getId()).stream()
                .map(RecurringRuleEntity::toSnapshot)
                .map(RecurringRule::fromSnapshot)
                .collect(Collectors.toList());
    }

    @Override
    public List<RecurringRule> findActiveRules() {
        return mongoRepository.findAllActiveRules().stream()
                .map(RecurringRuleEntity::toSnapshot)
                .map(RecurringRule::fromSnapshot)
                .collect(Collectors.toList());
    }

    @Override
    public List<RecurringRule> findActiveRulesByCashFlowId(CashFlowId cashFlowId) {
        return mongoRepository.findByCashFlowIdAndStatus(cashFlowId.id(), RuleStatus.ACTIVE).stream()
                .map(RecurringRuleEntity::toSnapshot)
                .map(RecurringRule::fromSnapshot)
                .collect(Collectors.toList());
    }

    @Override
    public void delete(RecurringRuleId ruleId) {
        mongoRepository.deleteById(ruleId.id());
    }

    @Override
    public long generateNextSequence() {
        SequenceDocument counter = mongoTemplate.findAndModify(
                query(where("_id").is(SEQUENCE_COLLECTION)),
                new Update().inc("seq", 1),
                options().returnNew(true).upsert(true),
                SequenceDocument.class
        );
        return counter != null ? counter.getSeq() : 1;
    }

    @org.springframework.data.mongodb.core.mapping.Document(collection = "database_sequences")
    private static class SequenceDocument {
        private String id;
        private long seq;

        public long getSeq() {
            return seq;
        }
    }
}
