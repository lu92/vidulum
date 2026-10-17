package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapper behind {@link JsonContent} is configured once, so many threads may read through it.
 *
 * <p>{@link #manyThreadsMayDeserialiseAtOnce()} is the regression guard for a race that was real:
 * {@code to()} used to call {@code findAndRegisterModules()} on every call, reconfiguring a static
 * mapper that two Kafka consumer threads share. A thread arriving mid-registration saw a mapper
 * without {@code ParameterNamesModule}, and then a class whose only constructor is the all-args one
 * had no creator Jackson would use.
 *
 * <p><b>This test did not reproduce that failure.</b> It was run against the old code on purpose and
 * passed, which is worth knowing before trusting it: a race needs an interleaving the test cannot
 * force, and the classloader variant needs threads that see different classpaths. It is a guard
 * against the configuration moving back into {@code to()} and a check that an all-args class still
 * round-trips — not evidence that no race exists.
 */
class JsonContentTest {

    /**
     * Deliberately shaped like the classes that broke: {@code @AllArgsConstructor} and no no-args
     * constructor, so Jackson can only build it through parameter names.
     */
    @Data
    @AllArgsConstructor
    static class OnlyAllArgs {
        private String name;
        private int count;
        private ZonedDateTime at;
    }

    @Test
    void readsBackWhatItWrote() {
        OnlyAllArgs original = new OnlyAllArgs("rent", 3, ZonedDateTime.parse("2026-10-17T08:00:00Z"));

        OnlyAllArgs readBack = JsonContent.asJson(original).to(OnlyAllArgs.class);

        assertThat(readBack).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void manyThreadsMayDeserialiseAtOnce() throws Exception {
        JsonContent json = JsonContent.asJson(
                new OnlyAllArgs("rent", 3, ZonedDateTime.parse("2026-10-17T08:00:00Z")));

        List<Callable<OnlyAllArgs>> reads = IntStream.range(0, 64)
                .<Callable<OnlyAllArgs>>mapToObj(i -> () -> json.to(OnlyAllArgs.class))
                .toList();

        ExecutorService threads = Executors.newFixedThreadPool(16);
        try {
            List<Future<OnlyAllArgs>> results = threads.invokeAll(reads);
            for (Future<OnlyAllArgs> result : results) {
                // get() rethrows whatever the read threw - an unconfigured mapper shows up here as
                // "no Creators, like default constructor, exist".
                assertThat(result.get().getName()).isEqualTo("rent");
            }
        } finally {
            threads.shutdownNow();
        }
    }
}
