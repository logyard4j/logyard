package com.logyard4j.output.console.style;

import com.logyard4j.output.console.terminal.ColorCapability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AnsiStyleTest {
    @Test
    void normalizesNamedColorsAndRendersTextDecorationsInTheirDeclaredOrder() {
        AnsiStyle style = new AnsiStyle(" Bright-Red ", "blue", true, true, true, true);

        assertEquals("bright_red", style.foreground());
        assertEquals("\u001B[1;2;3;4;91;44mvalue\u001B[0m", style.render("value", true));
    }

    @Test
    void adaptsRgbAndIndexedColorsToTheTerminalCapability() {
        AnsiStyle rgb = new AnsiStyle("#ff0000", null, false, false, false, false);
        AnsiStyle indexed = new AnsiStyle("196", null, false, false, false, false);

        assertEquals("\u001B[38;2;255;0;0mvalue\u001B[0m", rgb.render("value", true, ColorCapability.TRUECOLOR));
        assertEquals("\u001B[38;5;196mvalue\u001B[0m", rgb.render("value", true, ColorCapability.ANSI256));
        assertEquals("\u001B[31mvalue\u001B[0m", rgb.render("value", true, ColorCapability.ANSI16));
        assertEquals("\u001B[31mvalue\u001B[0m", indexed.render("value", true, ColorCapability.ANSI16));
    }

    @Test
    void rejectsUnsupportedColorsDuringConstruction() {
        IllegalArgumentException unsupported = assertThrows(
                IllegalArgumentException.class,
                () -> new AnsiStyle("ultraviolet", null, false, false, false, false));
        IllegalArgumentException indexed = assertThrows(
                IllegalArgumentException.class,
                () -> new AnsiStyle("256", null, false, false, false, false));

        assertEquals("unsupported terminal color: ultraviolet", unsupported.getMessage());
        assertEquals("ANSI color index must be between 0 and 255: 256", indexed.getMessage());
    }
}
