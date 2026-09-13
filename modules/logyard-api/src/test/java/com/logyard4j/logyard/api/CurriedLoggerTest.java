package com.logyard4j.logyard.api;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.ingress.IngressMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the preset-attribute semantics of the curried logger returned by {@code with}. */
final class CurriedLoggerTest {
    private final RecordingLogger delegate = new RecordingLogger();

    @Test
    void curriedLoggersDelegateIdentityAndLevelChecks() {
        LogyardLogger curried = delegate.with("tenant", "north");
        assertEquals("recording", curried.name());
        assertTrue(curried.isEnabled(Level.INFO));
    }

    @Test
    void presetsReachTheBuilderAsOneMergedSet() {
        delegate.with("tenant", "north").with("stage", "checkout").atInfo().log("event");
        assertEquals(1, delegate.builder.merged.size(), "currying twice must stay one wrapper deep");
        AttributeSet preset = delegate.builder.merged.getFirst();
        assertEquals("north", preset.get("tenant"));
        assertEquals("checkout", preset.get("stage"));
    }

    @Test
    void laterPresetsReplaceEarlierPresetsOnTheSameKey() {
        delegate.with("stage", "first").with("stage", "second").atInfo().log("event");
        assertEquals("second", delegate.builder.merged.getFirst().get("stage"));
    }

    @Test
    void eventAttributesReplacePresetsOnTheIngressPath() {
        AttributeSet event = AttributeSet.builder(2)
                .put("stage", "event")
                .put("order.id", 7L)
                .build();
        delegate.with(AttributeSet.builder(2).put("stage", "preset").put("tenant", "north").build())
                .log(Level.INFO, null, "event", null, event, null);

        assertEquals("event", delegate.attributes.get("stage"), "event attributes win over presets");
        assertEquals("north", delegate.attributes.get("tenant"));
        assertEquals(7L, delegate.attributes.get("order.id"));
    }

    @Test
    void convenienceMethodsCarryPresetsThroughTheSharedIngress() {
        delegate.with("tenant", "north").info("plain {}", "message");
        assertEquals("north", delegate.attributes.get("tenant"));
        assertSame(IngressMetadata.current(), delegate.metadata, "currying must not rewrite source metadata");
    }

    @Test
    void presetsPassThroughUnmergedWhenAnEventCarriesNone() {
        AttributeSet preset = AttributeSet.of("tenant", "north");
        delegate.with(preset).info("plain message");
        assertSame(preset, delegate.attributes, "an empty event attribute set must not allocate a merge");
    }

    @Test
    void reservedPresetKeysFailWhereTheyAreDeclared() {
        assertThrows(IllegalArgumentException.class, () -> delegate.with("logyard.tenant", "north"));
    }

    private static final class RecordingLogger implements LogyardLogger {
        private final RecordingBuilder builder = new RecordingBuilder();
        private AttributeSet attributes;
        private IngressMetadata metadata;

        @Override public String name() { return "recording"; }
        @Override public boolean isEnabled(Level level) { return true; }
        @Override public LogBuilder at(Level level) { return builder; }

        @Override
        public void log(
                Level level,
                String eventName,
                String messageTemplate,
                Object[] messageArguments,
                AttributeSet eventAttributes,
                Throwable error) {
            log(level, eventName, messageTemplate, messageArguments, eventAttributes, error, IngressMetadata.current());
        }

        @Override
        public void log(
                Level level,
                String eventName,
                String messageTemplate,
                Object[] messageArguments,
                AttributeSet eventAttributes,
                Throwable error,
                IngressMetadata ingressMetadata) {
            attributes = eventAttributes;
            metadata = ingressMetadata;
        }
    }

    private static final class RecordingBuilder implements LogBuilder {
        private final List<AttributeSet> merged = new ArrayList<>();

        @Override public LogBuilder event(String value) { return this; }
        @Override public LogBuilder message(String value) { return this; }
        @Override public LogBuilder argument(Object value) { return this; }
        @Override public LogBuilder argumentLazy(Supplier<?> valueSupplier) { return this; }
        @Override public LogBuilder add(String key, Object value) { return this; }
        @Override public LogBuilder addLazy(String key, Supplier<?> valueSupplier) { return this; }
        @Override public LogBuilder cause(Throwable value) { return this; }
        @Override public void log() { }
        @Override public void log(String value) { }
        @Override public void log(String value, Object... values) { }

        @Override
        public LogBuilder addAll(AttributeSet values) {
            merged.add(values);
            return this;
        }
    }
}
