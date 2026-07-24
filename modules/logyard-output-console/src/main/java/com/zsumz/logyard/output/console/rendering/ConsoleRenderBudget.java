package com.zsumz.logyard.output.console.rendering;

import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.Objects;
import java.util.function.Consumer;

/** Enforces one hard character and line budget across the primary line and exception tree. */
final class ConsoleRenderBudget {
    private int remainingCharacters = ConsoleOutputLimits.MAX_EVENT_CHARACTERS;
    private int remainingLines = CaptureLimits.MAX_EVENT_ENTRIES;
    private boolean exhausted;

    void emit(String line, Consumer<String> output) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(output, "output");
        if (remainingLines == 0 || remainingCharacters <= 1) {
            exhausted = true;
            return;
        }
        String bounded = ConsoleText.truncate(line, remainingCharacters - 1);
        output.accept(bounded);
        remainingCharacters -= bounded.length() + 1;
        remainingLines--;
        if (bounded.length() < line.length()) {
            exhausted = true;
        }
    }

    boolean exhausted() {
        return exhausted || remainingLines == 0 || remainingCharacters <= 1;
    }
}
