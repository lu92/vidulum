package com.multi.vidulum.common;

// CashChangeId and CashFlowId are now in common package - no import needed
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.LongStream;

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
        return generateCashChangeIds(1).getFirst();
    }

    /**
     * Generates {@code count} consecutive CashChangeIds in <b>one</b> round trip to the sequence.
     *
     * <p>The reason this exists: every id used to cost its own {@code findAndModify}, so a daily
     * recurring rule - 365 occurrences inside the forecast window - paid 365 of them before a single
     * cash change was written, and a 402-row CSV import paid 402. Reserving a range costs one
     * {@code $inc} by {@code count}.
     *
     * <p>{@link #generateCashChangeId()} delegates here rather than keeping its own copy of the
     * arithmetic: two paths computing one range is how the two halves drift apart, and the id format
     * is exactly the sort of thing that drifts silently.
     *
     * @param count how many ids to reserve; zero or less reserves nothing and touches no database
     * @return the ids in ascending order, consecutive
     */
    public List<CashChangeId> generateCashChangeIds(int count) {
        if (count <= 0) {
            return List.of();
        }

        long last = reserveSequenceRange(CASHCHANGE_SEQUENCE, INITIAL_VALUE_10_DIGITS, count);

        return LongStream.rangeClosed(last - count + 1, last)
                .mapToObj(seq -> CashChangeId.of(String.format("CC%010d", seq)))
                .toList();
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
        return reserveSequenceRange(sequenceName, initialValue, 1);
    }

    /**
     * Reserves {@code count} consecutive values from a named sequence and answers the <b>last</b>
     * one, so the range taken is {@code [answer - count + 1, answer]}.
     *
     * <p>One {@code findAndModify} per call, whatever the count: MongoDB applies {@code $inc}
     * atomically, so two instances asking at the same moment take disjoint ranges.
     *
     * <p><b>The guard is {@code value - count < initialValue}, not {@code value <= initialValue}.</b>
     * The latter is what the single-value version said, and it is wrong once a range is involved: a
     * cold sequence answers {@code count} after the upsert, and a sequence left below the digit
     * floor would otherwise hand out a range reaching under it - ids that break their own format.
     * For {@code count == 1} the two conditions agree exactly, which is what lets the single-id path
     * run through here unchanged.
     */
    private long reserveSequenceRange(String sequenceName, long initialValue, int count) {
        Query query = new Query(Criteria.where("_id").is(sequenceName));
        Update update = new Update().inc("value", count);
        FindAndModifyOptions options = FindAndModifyOptions.options()
                .returnNew(true)
                .upsert(true);

        SequenceDocument result = mongoTemplate.findAndModify(query, update, options, SequenceDocument.class);

        if (result == null || result.getValue() - count < initialValue) {
            // The sequence is new, or was left below the floor: reserve the first range above it
            long last = initialValue + count;
            mongoTemplate.save(new SequenceDocument(sequenceName, last));
            return last;
        }

        return result.getValue();
    }
}
