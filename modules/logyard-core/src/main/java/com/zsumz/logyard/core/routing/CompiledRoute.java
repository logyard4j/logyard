package com.zsumz.logyard.core.routing;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.spi.processing.EventProcessor;
import com.zsumz.logyard.api.spi.output.EventSink;

import java.util.List;

public record CompiledRoute(
        Level level,
        EventSink sink,
        EventProcessor[] processors,
        List<String> outputNames,
        List<String> processorNames,
        String matchedRule,
        PlanEpoch epoch) {
}
