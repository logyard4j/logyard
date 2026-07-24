package com.zsumz.logyard.tests;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.event.MessageFormatter;
import com.zsumz.logyard.core.processing.RedactionProcessor;
import com.zsumz.logyard.output.console.rendering.ConsoleEventRenderer;
import com.zsumz.logyard.output.console.style.BuiltInThemes;
import com.zsumz.logyard.output.console.terminal.ColorCapability;
import com.zsumz.logyard.output.json.encoding.JsonEncoder;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Constrained-heap process probe for the adversarial graphs and scalars guarded by event capture. */
public final class AdversarialBoundednessMain {
    private static final int MAX_JSON_CHARACTERS = 262_144;
    private static final int MAX_CONSOLE_CHARACTERS = 131_072;

    private AdversarialBoundednessMain() {
    }

    public static void main(String[] args) {
        Object graph = sharedGraph();
        RuntimeException failure = repeatedExceptionGraph();
        BigDecimal compactExponent = new BigDecimal(BigInteger.ONE, -1_000_000);
        LogEvent event = LogEvent.captureDeferred(
                1L,
                2L,
                Level.ERROR,
                "boundedness.probe",
                "probe.failed",
                "{} {}",
                () -> new Object[] {graph, HostileEnum.VALUE},
                () -> AttributeSet.builder()
                        .put("graph", graph)
                        .put("exact.decimal", compactExponent)
                        .build(),
                2,
                failure,
                3L,
                "boundedness");

        require(HostileEnum.toStringCalls.get() == 0, "enum toString ran during capture");
        require(event.argumentAt(1).equals("VALUE"), "enum name was not captured");
        require(event.attributes().get("graph").equals("[shared reference]"), "shared graph was expanded twice");
        require(event.exception().truncated(), "shared exception graph did not report truncation");
        require(event.renderedMessage().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS, "message exceeded its cap");

        String json = new JsonEncoder(ResourceAttributes.service("probe", "test", "1")).encode(event);
        require(json.length() <= MAX_JSON_CHARACTERS, "JSON exceeded its cap");
        require(json.startsWith("{") && json.endsWith("}"), "JSON record is incomplete");
        require(json.contains("[shared reference]"), "JSON omitted the shared-value marker");
        require(json.contains("[shared exception reference]"), "JSON omitted the shared-exception marker");

        AtomicInteger consoleCharacters = new AtomicInteger();
        ConsoleEventRenderer.create(
                        false,
                        BuiltInThemes.ember(),
                        ColorCapability.ANSI16,
                        ZoneOffset.UTC,
                        false,
                        true,
                        null)
                .render(event, line -> consoleCharacters.addAndGet(line.length() + 1));
        require(consoleCharacters.get() <= MAX_CONSOLE_CHARACTERS, "console output exceeded its cap");

        Object deep = "leaf";
        for (int depth = 0; depth < 10_000; depth++) {
            deep = List.of(deep);
        }
        String rendered = MessageFormatter.safeToString(deep);
        require(rendered.contains("[maximum nesting depth reached]"), "deep rendering missed its depth marker");

        LogEvent redacted = new RedactionProcessor(List.of("authorization")).process(new LogEvent(
                1L,
                2L,
                Level.INFO,
                "boundedness.probe",
                null,
                "redaction",
                null,
                AttributeSet.of("request", Map.of("request.authorization", "secret")),
                null,
                3L,
                "boundedness"));
        require(
                ((Map<?, ?>) redacted.attributes().get("request")).get("request.authorization").equals("[REDACTED]"),
                "nested dotted-key redaction failed");

        System.out.println("Adversarial boundedness verification passed under constrained heap");
    }

    private static Object sharedGraph() {
        Object level = List.of("leaf");
        for (int depth = 0; depth < 4; depth++) {
            level = java.util.Collections.nCopies(CaptureLimits.MAX_COLLECTION_ELEMENTS, level);
        }
        return level;
    }

    private static RuntimeException repeatedExceptionGraph() {
        IllegalStateException shared = new IllegalStateException("shared");
        RuntimeException parent = new RuntimeException("parent");
        for (int index = 0; index < 8; index++) {
            parent.addSuppressed(shared);
        }
        return parent;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private enum HostileEnum {
        VALUE;

        private static final AtomicInteger toStringCalls = new AtomicInteger();

        @Override
        public String toString() {
            toStringCalls.incrementAndGet();
            throw new AssertionError("hostile enum toString");
        }
    }
}
