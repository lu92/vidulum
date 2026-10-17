package com.multi.vidulum.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.SneakyThrows;

/**
 * JSON carried as text, with one mapper behind it.
 *
 * <p><b>The mapper is configured once and never touched again</b>, and that is the whole point of
 * this class's shape. {@link #to(Class)} used to call {@code findAndRegisterModules()} on every
 * single call — reconfiguring a {@code static} mapper that two Kafka consumer threads share, since
 * both {@code CashFlowEventListener} and {@code UserFinancialProfileCashFlowListener} read the
 * {@code cash_flow} topic and deserialise the envelope's content.
 *
 * <p>Two things made that unsafe. An {@code ObjectMapper} is thread-safe only after its
 * configuration is complete — registering a module resets the caches it builds for serialisers and
 * creators, so a thread arriving mid-registration can read through a half-configured mapper. And
 * {@code findAndRegisterModules()} discovers modules through {@code ServiceLoader} using the
 * <b>calling thread's</b> context classloader, so a call from a thread that sees less of the
 * classpath registers less — permanently, for every other caller, because the mapper is shared.
 * Either way the casualty is the same: without {@code ParameterNamesModule} any class relying on
 * implicit creator detection — here, every {@code @AllArgsConstructor} class with no no-args
 * constructor — fails with {@code "no Creators, like default constructor, exist"}.
 *
 * <p><b>How far the evidence goes.</b> Five tests in {@code CashFlowForecastProcessorTest} errored
 * with exactly that message during one full-reactor run, and passed in isolation. They did not
 * error again: the same suite then ran green twice, once still on the old code. So the link between
 * this pattern and those five errors is <i>inferred from the mechanism</i>, not demonstrated — and
 * {@code JsonContentTest} does not reproduce it either. What stands on its own is that
 * reconfiguring shared state on every read has no defence, whichever of the two mechanisms fired.
 *
 * <p>{@code findAndRegisterModules()} now runs in the static initialiser, where it belongs: once,
 * under this class's own classloader, before any thread can read.
 */
public record JsonContent(String content) {

    private static final ObjectMapper OBJECT_MAPPER;

    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
        // Everything else the classpath offers - ParameterNamesModule above all, which is what lets
        // an all-args constructor serve as a creator. Duplicate registrations are ignored by
        // default, so naming JavaTimeModule explicitly above stays harmless.
        OBJECT_MAPPER.findAndRegisterModules();
        OBJECT_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @SneakyThrows
    public static JsonContent asPrettyJson(Object object) {
        return new JsonContent(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(object));
    }

    @SneakyThrows
    public static JsonContent asJson(Object object) {
        return new JsonContent(OBJECT_MAPPER.writeValueAsString(object));
    }

    public <T> T to(Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(content, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
