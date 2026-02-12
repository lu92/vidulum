package com.multi.vidulum.common;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Generates unique business IDs using MongoDB atomic counters.
 * Ensures uniqueness across multiple application instances.
 *
 * ID formats:
 * - UserId: U + 8 digits (e.g., U10000001)
 * - CashFlowId: CF + 8 digits (e.g., CF10000001)
 * - CashChangeId: CC + 10 digits (e.g., CC1000000001)
 *
 * Starting value: 10000000 for 8-digit IDs, 1000000000 for 10-digit IDs
 */
@Service
@RequiredArgsConstructor
public class BusinessIdGenerator {

    private final MongoTemplate mongoTemplate;

    private static final long INITIAL_VALUE = 10000000L;
    private static final long INITIAL_VALUE_10_DIGITS = 1000000000L;
    private static final String USER_SEQUENCE = "user_sequence";
    private static final String CASHFLOW_SEQUENCE = "cashflow_sequence";
    private static final String CASHCHANGE_SEQUENCE = "cashchange_sequence";

    /**
     * Generates a new unique UserId.
     * Format: U + 8 digits (e.g., U10000001)
     *
     * @return new UserId with human-readable format
     */
    public UserId generateUserId() {
        long seq = getNextSequence(USER_SEQUENCE, INITIAL_VALUE);
        return UserId.of(String.format("U%08d", seq));
    }

    /**
     * Generates a new unique CashFlowId.
     * Format: CF + 8 digits (e.g., CF10000001)
     *
     * @return new CashFlowId with human-readable format
     */
    public CashFlowId generateCashFlowId() {
        long seq = getNextSequence(CASHFLOW_SEQUENCE, INITIAL_VALUE);
        return CashFlowId.of(String.format("CF%08d", seq));
    }

    /**
     * Generates a new unique CashChangeId.
     * Format: CC + 10 digits (e.g., CC1000000001)
     *
     * @return new CashChangeId with human-readable format
     */
    public CashChangeId generateCashChangeId() {
        long seq = getNextSequence(CASHCHANGE_SEQUENCE, INITIAL_VALUE_10_DIGITS);
        return CashChangeId.of(String.format("CC%010d", seq));
    }

    /**
     * Gets the next value from a named sequence using MongoDB's atomic findAndModify.
     * If the sequence doesn't exist, it's created with the specified initial value.
     *
     * @param sequenceName the name of the sequence
     * @param initialValue the initial value for new sequences
     * @return the next sequence value
     */
    private long getNextSequence(String sequenceName, long initialValue) {
        Query query = new Query(Criteria.where("_id").is(sequenceName));
        Update update = new Update().inc("value", 1);
        FindAndModifyOptions options = FindAndModifyOptions.options()
                .returnNew(true)
                .upsert(true);

        SequenceDocument result = mongoTemplate.findAndModify(query, update, options, SequenceDocument.class);

        if (result == null || result.getValue() <= initialValue) {
            // Initialize sequence if it's new or below initial value
            mongoTemplate.save(new SequenceDocument(sequenceName, initialValue + 1));
            return initialValue + 1;
        }

        return result.getValue();
    }
}
