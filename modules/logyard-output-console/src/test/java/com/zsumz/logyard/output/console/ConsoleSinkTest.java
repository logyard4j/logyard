package com.zsumz.logyard.output.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class ConsoleSinkTest {
    @Test
    void escapesControlCharactersAndCanRenderWithoutAnsi() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleSink sink = new ConsoleSink(
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                false,
                BuiltInThemes.ember(),
                ColorCapability.TRUECOLOR,
                ZoneOffset.UTC,
                true,
                false);
        sink.accept(new LogEvent(
                0,
                0,
                Level.INFO,
                "com.acme.Service",
                "order.accepted",
                "hello {}",
                new Object[] {"world\u001b[31m"},
                AttributeSet.of("request.id", "a\nb"),
                null,
                1,
                "main"));
        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("hello world\\u001b[31m"));
        assertTrue(rendered.contains("request.id=a\\nb"));
        assertFalse(rendered.contains("\u001b["));
    }
}
