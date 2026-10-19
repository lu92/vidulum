package com.multi.vidulum.common;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reserving a range of ids, and the arithmetic that turns it into ids.
 *
 * <p>MongoDB's own guarantee - that {@code $inc} is atomic, so two instances take disjoint ranges -
 * is not ours to test. What is ours is the <b>count of round trips</b> and the <b>range
 * arithmetic</b>: one off-by-one here either reuses an id or leaves a hole, and both are invisible
 * until someone looks at the data. A mocked {@code MongoTemplate} is the right instrument for both.
 *
 * <p>That a second reservation does not overlap the first <i>against a real sequence</i> is asserted
 * in {@code BusinessIdGeneratorMongoTest}, where a real MongoDB runs.
 */
class BusinessIdGeneratorTest {

    private static final long TEN_DIGIT_FLOOR = 1_000_000_000L;

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final BusinessIdGenerator generator = new BusinessIdGenerator(mongoTemplate);

    @Test
    void aRangeOfIdsCostsOneRoundTripAndIncrementsByTheWholeCount() {
        sequenceAnswers(TEN_DIGIT_FLOOR + 5);

        List<CashChangeId> ids = generator.generateCashChangeIds(5);

        assertThat(ids).extracting(CashChangeId::id).containsExactly(
                "CC1000000001", "CC1000000002", "CC1000000003", "CC1000000004", "CC1000000005");
        verify(mongoTemplate, times(1))
                .findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(SequenceDocument.class));
        assertThat(incrementAsked()).isEqualTo(5);
    }

    /**
     * The range runs backwards from the answer, so this is where an off-by-one would live: a
     * sequence already at the floor plus five, asked for three more, owes exactly 6, 7 and 8.
     */
    @Test
    void aSecondRangeStartsWhereTheFirstEnded() {
        sequenceAnswers(TEN_DIGIT_FLOOR + 8);

        List<CashChangeId> ids = generator.generateCashChangeIds(3);

        assertThat(ids).extracting(CashChangeId::id)
                .containsExactly("CC1000000006", "CC1000000007", "CC1000000008");
    }

    /**
     * After the upsert creates it, a cold sequence answers {@code count} - far below the ten-digit
     * floor - so the whole range has to be moved above it, not just the first value. This is the
     * case where the single-id guard ({@code value <= initialValue}) would have handed out one id
     * and dropped the rest of the range.
     */
    @Test
    void aColdSequenceReservesItsFirstRangeAboveTheDigitFloor() {
        sequenceAnswers(5);

        List<CashChangeId> ids = generator.generateCashChangeIds(5);

        assertThat(ids).extracting(CashChangeId::id).containsExactly(
                "CC1000000001", "CC1000000002", "CC1000000003", "CC1000000004", "CC1000000005");
        assertThat(sequenceStoredOnReset().getValue())
                .as("the sequence must be left holding the end of the range, or the next caller repeats it")
                .isEqualTo(TEN_DIGIT_FLOOR + 5);
    }

    @Test
    void anAbsentSequenceDocumentIsTreatedAsACold() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(SequenceDocument.class)))
                .thenReturn(null);

        assertThat(generator.generateCashChangeIds(2)).extracting(CashChangeId::id)
                .containsExactly("CC1000000001", "CC1000000002");
    }

    /**
     * The single-id method delegates to the range one, so there is one copy of this arithmetic. The
     * assertion that proves the delegation is the increment: it must be 1, not the old constant.
     */
    @Test
    void theSingleIdPathGoesThroughTheSameArithmetic() {
        sequenceAnswers(TEN_DIGIT_FLOOR + 7);

        CashChangeId id = generator.generateCashChangeId();

        assertThat(id.id()).isEqualTo("CC1000000007");
        assertThat(incrementAsked()).isEqualTo(1);
        verify(mongoTemplate, times(1))
                .findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(SequenceDocument.class));
    }

    @Test
    void askingForNothingTouchesNoDatabase() {
        assertThat(generator.generateCashChangeIds(0)).isEmpty();
        assertThat(generator.generateCashChangeIds(-3)).isEmpty();

        verifyNoInteractions(mongoTemplate);
    }

    /**
     * The other two sequences are eight-digit and were not meant to change; this pins that the
     * shared helper did not quietly alter them while the range version was threaded through it.
     */
    @Test
    void theEightDigitSequencesStillAdvanceByOne() {
        sequenceAnswers(10_000_042L);
        assertThat(generator.generateUserId().getId()).isEqualTo("U10000042");
        assertThat(incrementAsked()).isEqualTo(1);

        sequenceAnswers(10_000_043L);
        assertThat(generator.generateCashFlowId().id()).isEqualTo("CF10000043");
    }

    private void sequenceAnswers(long value) {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(SequenceDocument.class)))
                .thenReturn(new SequenceDocument("any_sequence", value));
    }

    private long incrementAsked() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, org.mockito.Mockito.atLeastOnce())
                .findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(SequenceDocument.class));
        Document inc = (Document) update.getValue().getUpdateObject().get("$inc");
        return ((Number) inc.get("value")).longValue();
    }

    private SequenceDocument sequenceStoredOnReset() {
        ArgumentCaptor<SequenceDocument> stored = ArgumentCaptor.forClass(SequenceDocument.class);
        verify(mongoTemplate).save(stored.capture());
        return stored.getValue();
    }
}
