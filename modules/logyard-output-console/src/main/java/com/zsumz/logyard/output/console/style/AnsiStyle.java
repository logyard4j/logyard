package com.zsumz.logyard.output.console.style;

import com.zsumz.logyard.output.console.terminal.ColorCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable semantic terminal style. */
public record AnsiStyle(
        String foreground,
        String background,
        boolean bold,
        boolean dim,
        boolean italic,
        boolean underline) {
    public static final AnsiStyle PLAIN = new AnsiStyle(null, null, false, false, false, false);
    static final String RESET = "\u001B[0m";

    public AnsiStyle {
        foreground = AnsiColorCodes.normalize(foreground);
        background = AnsiColorCodes.normalize(background);
        AnsiColorCodes.validate(foreground);
        AnsiColorCodes.validate(background);
    }

    public String render(String value, boolean enabled) {
        return render(value, enabled, ColorCapability.TRUECOLOR);
    }

    public String render(String value, boolean enabled, ColorCapability capability) {
        Objects.requireNonNull(value, "value");
        if (!enabled || equals(PLAIN)) {
            return value;
        }
        return prefix(Objects.requireNonNull(capability, "capability")) + value + RESET;
    }

    String prefix(ColorCapability capability) {
        List<String> codes = new ArrayList<>(6);
        if (bold) {
            codes.add("1");
        }
        if (dim) {
            codes.add("2");
        }
        if (italic) {
            codes.add("3");
        }
        if (underline) {
            codes.add("4");
        }
        AnsiColorCodes.appendTo(codes, foreground, false, capability);
        AnsiColorCodes.appendTo(codes, background, true, capability);
        return codes.isEmpty() ? "" : "\u001B[" + String.join(";", codes) + "m";
    }
}
