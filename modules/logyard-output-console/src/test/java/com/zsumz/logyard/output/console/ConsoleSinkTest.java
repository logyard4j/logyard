package com.zsumz.logyard.output.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.TextFormatter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class ConsoleSinkTest {
    @Test
    void escapesControlCharactersAndCanRenderWithoutAnsi() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleSink sink = sink(bytes, true, false, null);
        sink.accept(event(
                "hello {}",
                new Object[] {"world\u001b[31m"},
                AttributeSet.of("request.id", "a\nb"),
                null));
        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("hello world\\u001b[31m"));
        assertTrue(rendered.contains("request.id=a\\nb"));
        assertFalse(rendered.contains("\u001b["));
    }

    @Test
    void adaptsCustomFormattersToOneSanitizedConsoleRecord() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleSink sink = sink(bytes, true, false, ignored -> "custom\nrecord");

        sink.accept(event("ignored", null, AttributeSet.EMPTY, null));

        assertEquals("custom\\nrecord\n", bytes.toString(StandardCharsets.UTF_8));
        assertEquals("custom-text", sink.health("console").details().get("format"));
    }

    @Test
    void rendersSuppressedCausesAndCollapsesCommonFrames() {
        StackTraceElement common = new StackTraceElement("com.acme.Worker", "run", "Worker.java", 42);
        IllegalArgumentException cause = new IllegalArgumentException("cause");
        cause.setStackTrace(new StackTraceElement[] {common});
        IllegalStateException failure = new IllegalStateException("outer", cause);
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.acme.Service", "call", "Service.java", 10),
                common
        });
        failure.addSuppressed(new IllegalStateException("suppressed"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleSink sink = sink(bytes, false, true, null);

        sink.accept(event("failed", null, AttributeSet.EMPTY, failure));

        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Suppressed: java.lang.IllegalStateException: suppressed"));
        assertTrue(rendered.contains("Caused by: java.lang.IllegalArgumentException: cause"));
        assertTrue(rendered.contains("1 common frame(s)"));
    }

    private static ConsoleSink sink(
            ByteArrayOutputStream bytes,
            boolean compactExceptions,
            boolean collapseCommonFrames,
            TextFormatter formatter) {
        return new ConsoleSink(
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                false,
                BuiltInThemes.ember(),
                ColorCapability.TRUECOLOR,
                ZoneOffset.UTC,
                compactExceptions,
                collapseCommonFrames,
                false,
                formatter);
    }

    private static LogEvent event(String template, Object[] arguments, AttributeSet attributes, Throwable exception) {
        return new LogEvent(
                0,
                0,
                Level.INFO,
                "com.acme.Service",
                "order.accepted",
                template,
                arguments,
                attributes,
                exception,
                1,
                "main");
    }
}
