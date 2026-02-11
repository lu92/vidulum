package com.multi.vidulum.common;

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
 *
 * Starting value: 10000000 (so first ID is 10000001)
 */
@Service
@RequiredArgsConstructor
public class BusinessIdGenerator {

    private final MongoTemplate mongoTemplate;

    private static final long INITIAL_VALUE = 10000000L;
    private static final String USER_SEQUENCE = "user_sequence";

    /**
     * Generates a new unique UserId.
     * Format: U + 8 digits (e.g., U10000001)
     *
     * @return new UserId with human-readable format
     */
    public UserId generateUserId() {
        long seq = getNextSequence(USER_SEQUENCE);
        return UserId.of(String.format("U%08d", seq));
    }

    /**
     * Gets the next value from a named sequence using MongoDB's atomic findAndModify.
     * If the sequence doesn't exist, it's created with INITIAL_VALUE.
     *
     * @param sequenceName the name of the sequence
     * @return the next sequence value
     */
    private long getNextSequence(String sequenceName) {
        Query query = new Query(Criteria.where("_id").is(sequenceName));
        Update update = new Update().inc("value", 1);
        FindAndModifyOptions options = FindAndModifyOptions.options()
                .returnNew(true)
                .upsert(true);

        SequenceDocument result = mongoTemplate.findAndModify(query, update, options, SequenceDocument.class);

        if (result == null || result.getValue() <= INITIAL_VALUE) {
            // Initialize sequence if it's new or below initial value
            mongoTemplate.save(new SequenceDocument(sequenceName, INITIAL_VALUE + 1));
            return INITIAL_VALUE + 1;
        }

        return result.getValue();
    }
}
