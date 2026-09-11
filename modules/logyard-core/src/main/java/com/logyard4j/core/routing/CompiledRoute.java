package com.logyard4j.core.routing;

import com.logyard4j.api.Level;
import com.logyard4j.api.spi.processing.EventProcessor;
import com.logyard4j.api.spi.output.EventSink;

import java.util.List;
import java.util.Objects;

public record CompiledRoute(
        Level level,
        int enabledMask,
        EventSink sink,
        EventProcessor[] processors,
        List<String> outputNames,
        List<String> processorNames,
        String matchedRule,
        PlanEpoch epoch) {
    private static final int ALL_LEVELS_MASK = (1 << Level.values().length) - 1;

    public CompiledRoute {
        Objects.requireNonNull(level, "level");
        if ((enabledMask & ~ALL_LEVELS_MASK) != 0) {
            throw new IllegalArgumentException("enabledMask contains unsupported level bits");
        }
    }

    public CompiledRoute(
            Level level,
            EventSink sink,
            EventProcessor[] processors,
            List<String> outputNames,
            List<String> processorNames,
            String matchedRule,
            PlanEpoch epoch) {
        this(level, Level.enabledMaskFrom(level), sink, processors, outputNames, processorNames, matchedRule, epoch);
    }

    public boolean enables(Level candidate) {
        return (enabledMask & Objects.requireNonNull(candidate, "candidate").mask()) != 0;
    }
}
