package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.domain.MonthlyPattern;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The invariant that cost 40 integration tests before it existed: <b>after {@code save}, the
 * instance the caller still holds knows its stored version.</b>
 *
 * <p>Several flows save the same aggregate twice - creating a rule stores it and then
 * {@code RecurringRuleExecutor} stores the ids it generated; pausing clears the predictions and then
 * stores the pause. Without the write-back the second save sends a version of null, Spring Data
 * reads null as "new document", and Mongo answers {@code DuplicateKeyException} on the rule id.
 *
 * <p>This is the cheap net for it. The expensive one - {@code RecurringRulesHttpIntegrationTest} -
 * found the defect, but takes a server, a Mongo and a Kafka to say what these two assertions say in
 * milliseconds.
 */
class RecurringRuleRepositoryAdapterVersionTest {

    private static final Instant NOW = Instant.parse("2022-01-01T00:00:00Z");

    private final RecurringRuleMongoRepository mongoRepository = mock(RecurringRuleMongoRepository.class);
    private final RecurringRuleRepositoryAdapter adapter =
            new RecurringRuleRepositoryAdapter(mongoRepository, mock(MongoTemplate.class));

    @Test
    void savingARuleTeachesTheInstanceTheVersionStorageGaveIt() {
        RecurringRule rule = newRule();
        assertThat(rule.getSnapshot().version())
                .as("a rule that has never been stored carries no version")
                .isNull();
        storageAssigns(0L);

        adapter.save(rule);

        assertThat(rule.getSnapshot().version())
                .as("the caller's own instance, not a copy returned from somewhere")
                .isEqualTo(0L);
    }

    @Test
    void theSecondSaveOfTheSameInstanceCarriesTheVersionAndIsThereforeAnUpdate() {
        RecurringRule rule = newRule();
        storageAssigns(0L);
        adapter.save(rule);

        storageAssigns(1L);
        adapter.save(rule);

        ArgumentCaptor<RecurringRuleEntity> captor = ArgumentCaptor.forClass(RecurringRuleEntity.class);
        verify(mongoRepository, times(2)).save(captor.capture());

        assertThat(captor.getAllValues().get(0).getVersion())
                .as("first save is an insert, which is what a null version asks for")
                .isNull();
        assertThat(captor.getAllValues().get(1).getVersion())
                .as("second save is a conditional update - this is the assertion that was missing")
                .isEqualTo(0L);
        assertThat(rule.getSnapshot().version()).isEqualTo(1L);
    }

    /**
     * Mongo answers with the document it wrote, version included - this stub does the same, since
     * the entity has no setters and is only ever built from a snapshot.
     */
    private void storageAssigns(Long version) {
        when(mongoRepository.save(any(RecurringRuleEntity.class))).thenAnswer(invocation -> {
            RecurringRuleEntity given = invocation.getArgument(0);
            RecurringRuleSnapshot asStored = withVersion(given.toSnapshot(), version);
            return RecurringRuleEntity.fromSnapshot(asStored);
        });
    }

    private static RecurringRuleSnapshot withVersion(RecurringRuleSnapshot snapshot, Long version) {
        return new RecurringRuleSnapshot(
                snapshot.ruleId(), snapshot.userId(), snapshot.cashFlowId(), snapshot.name(),
                snapshot.description(), snapshot.baseAmount(), snapshot.categoryName(),
                snapshot.pattern(), snapshot.startDate(), snapshot.endDate(),
                snapshot.maxOccurrences(), snapshot.activeMonths(), snapshot.excludedDates(),
                snapshot.status(), snapshot.pauseInfo(), snapshot.amountChanges(),
                snapshot.executions(), snapshot.generatedCashChangeIds(), snapshot.createdAt(),
                snapshot.lastModifiedAt(), version);
    }

    private static RecurringRule newRule() {
        return RecurringRule.create(
                RecurringRuleId.generate(1),
                UserId.of("U10000001"),
                CashFlowId.of("CF10000001"),
                "Rent",
                "monthly rent",
                Money.of(-1500, "PLN"),
                new CategoryName("Housing"),
                new MonthlyPattern(15, 1, false),
                LocalDate.of(2022, 1, 1),
                null, null, null, null,
                NOW);
    }
}
