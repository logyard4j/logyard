package com.logyard4j.output.json.stream;

import com.logyard4j.output.json.testing.BlockedOutputProbe;
import com.logyard4j.output.json.testing.BlockedOutputProbe.Operation;
import com.logyard4j.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.Writer;
import java.time.Duration;

final class JsonLinesHealthConcurrencyTest {
    @ParameterizedTest
    @EnumSource(Operation.class)
    void directHealthRemainsResponsiveDuringOutputIo(Operation operation) throws Exception {
        verify(operation, false);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void asyncHealthRemainsResponsiveDuringOutputIo(Operation operation) throws Exception {
        verify(operation, true);
    }

    private static void verify(Operation operation, boolean async) throws Exception {
        BlockedOutputProbe probe = new BlockedOutputProbe(operation);
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        Writer writer = new Writer() {
            @Override
            public void write(char[] value, int offset, int length) {
                probe.visit(Operation.WRITE);
            }

            @Override
            public void flush() {
                probe.visit(Operation.FLUSH);
            }

            @Override
            public void close() {
                probe.visit(Operation.CLOSE);
            }
        };
        JsonLinesSink sink = new JsonLinesSink(writer, event -> "{}", Duration.ofSeconds(1), true, scheduler);
        probe.verify(sink, scheduler, async);
    }
}
