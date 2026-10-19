package com.multi.vidulum;

import com.multi.vidulum.common.BusinessIdGenerator;
import com.multi.vidulum.common.CashChangeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one question about id reservation that a mock cannot answer: does a real sequence actually
 * advance by the whole range, so that two reservations never overlap?
 *
 * <p>{@code BusinessIdGeneratorTest} covers the arithmetic and the round-trip count against a
 * mocked {@code MongoTemplate}. What is left is the {@code $inc} itself, and that needs MongoDB.
 *
 * <p>Nothing here asserts an absolute id. The {@code sequences} collection is deliberately not
 * wiped by any {@code DataCleaner} - ids are not meant to be reused - so its value depends on
 * whatever ran before. Only distance and distinctness are stable, and they are what the arithmetic
 * can get wrong.
 */
@ExtendWith(SpringExtension.class)
class BusinessIdGeneratorMongoTest extends AppIntegrationTest {

    @Autowired
    private BusinessIdGenerator businessIdGenerator;

    @Test
    void twoReservationsNeverOverlap() {
        List<CashChangeId> first = businessIdGenerator.generateCashChangeIds(3);
        List<CashChangeId> second = businessIdGenerator.generateCashChangeIds(3);

        assertThat(first).hasSize(3);
        assertThat(second).hasSize(3);
        assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(sequenceNumbersOf(first)).as("a reservation is consecutive").isEqualTo(
                consecutiveFrom(sequenceNumbersOf(first).getFirst(), 3));
        assertThat(sequenceNumbersOf(second).getFirst())
                .as("and the next one starts immediately after it, with no hole and no repeat")
                .isEqualTo(sequenceNumbersOf(first).getLast() + 1);
    }

    /**
     * The single-id method delegates to the range one, so both must draw from the same sequence. If
     * they ever stopped doing so, this is where two cash changes would end up sharing an id.
     */
    @Test
    void theSingleIdPathContinuesTheSameSequence() {
        List<CashChangeId> reserved = businessIdGenerator.generateCashChangeIds(2);
        CashChangeId next = businessIdGenerator.generateCashChangeId();

        assertThat(sequenceNumberOf(next))
                .isEqualTo(sequenceNumbersOf(reserved).getLast() + 1);
    }

    @Test
    void everyReservedIdKeepsTheTenDigitFormat() {
        assertThat(businessIdGenerator.generateCashChangeIds(4))
                .extracting(CashChangeId::id)
                .allMatch(id -> id.matches("CC\\d{10}"), "CC followed by ten digits");
    }

    private static List<Long> sequenceNumbersOf(List<CashChangeId> ids) {
        return ids.stream().map(BusinessIdGeneratorMongoTest::sequenceNumberOf).toList();
    }

    private static long sequenceNumberOf(CashChangeId id) {
        return Long.parseLong(id.id().substring(2));
    }

    private static List<Long> consecutiveFrom(long start, int count) {
        return java.util.stream.LongStream.range(0, count).map(i -> start + i).boxed().toList();
    }
}
