package com.logyard4j.output.console.rendering;

import com.logyard4j.api.event.ExceptionSnapshot;
import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.output.console.style.ConsoleTheme;
import com.logyard4j.output.console.terminal.ColorCapability;

import java.util.IdentityHashMap;
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

    void render(ExceptionSnapshot exception, ConsoleRenderBudget budget, Consumer<String> output) {
        render(exception, 0, null, false, Objects.requireNonNull(budget, "budget"),
                new IdentityHashMap<>(), Objects.requireNonNull(output, "output"));
    }

    private void render(
            ExceptionSnapshot exception,
            int depth,
            List<StackTraceElement> parentFrames,
            boolean suppressed,
            ConsoleRenderBudget budget,
            IdentityHashMap<ExceptionSnapshot, Boolean> seen,
            Consumer<String> output) {
        if (budget.exhausted()) {
            return;
        }
        if (depth >= CaptureLimits.MAX_NESTING_DEPTH || seen.put(exception, Boolean.TRUE) != null) {
            emit("stack_frame", "    ... shared or bounded exception reference", budget, output);
            return;
        }
        String prefix = depth == 0 ? "  " : suppressed ? "  Suppressed: " : "  Caused by: ";
        emit("exception", prefix + ConsoleText.sanitize(exception.summary()), budget, output);

        List<StackTraceElement> frames = exception.frames();
        int common = parentFrames == null || !collapseCommonFrames ? 0 : commonFrames(frames, parentFrames);
        int available = frames.size() - common;
        int limit = Math.min(available, compact ? COMPACT_STACK_FRAMES : FULL_STACK_FRAMES);
        for (int index = 0; index < limit && !budget.exhausted(); index++) {
            emit("stack_frame", "    at " + ConsoleText.sanitize(frames.get(index).toString()), budget, output);
        }
        if (available > limit) {
            emit("stack_frame", "    ... " + (available - limit) + " frame(s) truncated", budget, output);
        }
        if (common > 0) {
            emit("stack_frame", "    ... " + common + " common frame(s)", budget, output);
        }
        if (exception.truncated()) {
            emit("stack_frame", "    ... exception snapshot was bounded", budget, output);
        }
        for (ExceptionSnapshot current : exception.suppressed()) {
            render(current, depth + 1, frames, true, budget, seen, output);
        }
        if (exception.cause() != null) {
            render(exception.cause(), depth + 1, frames, false, budget, seen, output);
        }
    }

    private void emit(String role, String line, ConsoleRenderBudget budget, Consumer<String> output) {
        budget.emit(theme.role(role).render(line, colors, capability), output);
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
