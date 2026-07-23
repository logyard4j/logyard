package com.zsumz.logyard.output.console;

import com.zsumz.logyard.api.event.ExceptionSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class ConsoleExceptionRenderer {
    private static final int COMPACT_STACK_FRAMES = 8;
    private static final int FULL_STACK_FRAMES = 64;

    private final boolean colors;
    private final ConsoleTheme theme;
    private final ColorCapability capability;
    private final boolean compact;
    private final boolean collapseCommonFrames;

    ConsoleExceptionRenderer(
            boolean colors,
            ConsoleTheme theme,
            ColorCapability capability,
            boolean compact,
            boolean collapseCommonFrames) {
        this.colors = colors;
        this.theme = Objects.requireNonNull(theme, "theme");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.compact = compact;
        this.collapseCommonFrames = collapseCommonFrames;
    }

    void render(ExceptionSnapshot exception, Consumer<String> output) {
        render(exception, 0, null, false, Objects.requireNonNull(output, "output"));
    }

    private void render(
            ExceptionSnapshot exception,
            int depth,
            List<StackTraceElement> parentFrames,
            boolean suppressed,
            Consumer<String> output) {
        String prefix = depth == 0 ? "  " : suppressed ? "  Suppressed: " : "  Caused by: ";
        output.accept(theme.role("exception").render(prefix + ConsoleText.sanitize(exception.summary()), colors, capability));

        List<StackTraceElement> frames = exception.frames();
        int common = parentFrames == null || !collapseCommonFrames ? 0 : commonFrames(frames, parentFrames);
        int available = frames.size() - common;
        int limit = Math.min(available, compact ? COMPACT_STACK_FRAMES : FULL_STACK_FRAMES);
        for (int index = 0; index < limit; index++) {
            output.accept(theme.role("stack_frame").render(
                    "    at " + ConsoleText.sanitize(frames.get(index).toString()), colors, capability));
        }
        if (available > limit) {
            output.accept(theme.role("stack_frame").render(
                    "    ... " + (available - limit) + " frame(s) truncated", colors, capability));
        }
        if (common > 0) {
            output.accept(theme.role("stack_frame").render("    ... " + common + " common frame(s)", colors, capability));
        }
        if (exception.truncated()) {
            output.accept(theme.role("stack_frame").render("    ... exception snapshot was bounded", colors, capability));
        }
        for (ExceptionSnapshot current : exception.suppressed()) {
            render(current, depth + 1, frames, true, output);
        }
        if (exception.cause() != null) {
            render(exception.cause(), depth + 1, frames, false, output);
        }
    }

    private static int commonFrames(List<StackTraceElement> left, List<StackTraceElement> right) {
        int leftIndex = left.size() - 1;
        int rightIndex = right.size() - 1;
        int count = 0;
        while (leftIndex >= 0 && rightIndex >= 0 && left.get(leftIndex).equals(right.get(rightIndex))) {
            leftIndex--;
            rightIndex--;
            count++;
        }
        return count;
    }
}
