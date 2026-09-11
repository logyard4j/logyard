package com.zsumz.logyard.output.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.output.console.rendering.TemplateTextFormatter;
import com.zsumz.logyard.output.console.style.BuiltInThemes;
import com.zsumz.logyard.output.console.terminal.ColorCapability;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ConsoleIdentityTest {
    @Test
    void prettyAndTemplateOutputEscapeLoggerThreadAndEventControls() {
        for (char control : new char[] {'\r', '\n', '\u001b', '\u0085', '\u2028', '\u2029',
                '\u061c', '\u200e', '\u200f', '\u202a', '\u202e', '\u2066', '\u2069'}) {
            for (boolean template : new boolean[] {false, true}) {
                for (boolean colors : new boolean[] {false, true}) {
                    String identity = "test" + control + "FORGED";
                    String output = render(identity, identity, identity, template, colors);
                    String plain = output.replaceAll("\u001b\\[[0-9;]*m", "");
                    assertEquals(1, plain.lines().count());
                    assertFalse(plain.substring(0, plain.length() - 1).contains(Character.toString(control)));
                    assertTrue(plain.contains("real event"));
                    if (!colors) {
                        assertFalse(output.contains("\u001b"));
                    }
                }
            }
        }
    }

    @Test
    void arbitraryDottedNamesRenderWithinTheLoggerColumnWithoutSplittingUnicode() {
        for (String logger : List.of(
                "abcdefghijklmnop..qrstuvwxyzabcdefghijklmnop",
                ".".repeat(100), ".leading." + "part".repeat(12), "trailing.".repeat(8),
                "𐐀".repeat(100), "𐐀package.".repeat(12) + "𐐀Class",
                "a".repeat(34) + "𐐀" + "b".repeat(32))) {
            String output = render(logger, null, "", false, false);
            String column = output.substring("00:00:00.000 INFO  ".length(), output.indexOf(" real event"));
            assertEquals(34, column.codePointCount(0, column.length()));
            requireWholeSurrogates(output);
            assertEquals(1, output.lines().count());
        }
    }

    @Test
    void shortSupplementaryNamesUseOneCodePointPerColumnPosition() {
        String output = render("𐐀.Name", null, "", false, false);
        assertEquals("00:00:00.000 INFO  𐐀.Name" + " ".repeat(28) + " real event\n", output);
    }

    @Test
    void longTemplateIdentitiesRemainBoundedAndUnicodeSafe() {
        String identity = "𐐀.\u2028".repeat(1_024);
        String output = render(identity, identity, identity, true, false);
        assertTrue(output.length() < 32_768);
        assertEquals(1, output.lines().count());
        assertFalse(output.contains("\u2028"));
        requireWholeSurrogates(output);
    }

    private static String render(String logger, String eventName, String thread, boolean template, boolean colors) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ConsoleSink sink = new ConsoleSink(new PrintStream(bytes, true, StandardCharsets.UTF_8),
                colors, BuiltInThemes.ember(), ColorCapability.TRUECOLOR, ZoneOffset.UTC, true, true,
                false, template ? new TemplateTextFormatter("{logger} {event} {thread} {message}", ZoneOffset.UTC) : null)) {
            sink.accept(new LogEvent(0, 0, Level.INFO, logger, eventName, "real event", null,
                    AttributeSet.EMPTY, null, 1, thread));
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void requireWholeSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                assertTrue(index + 1 < value.length() && Character.isLowSurrogate(value.charAt(++index)));
            } else {
                assertFalse(Character.isLowSurrogate(character));
            }
        }
        assertFalse(value.contains("?"));
    }
}
