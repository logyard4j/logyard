package com.logyard4j.logyard.output.json.stream;

import com.logyard4j.logyard.output.json.encoding.JsonEncoder;
import com.logyard4j.logyard.output.json.encoding.ResourceAttributes;
import com.logyard4j.logyard.output.json.testing.BlockedOutputProbe;
import com.logyard4j.logyard.output.json.testing.BlockedOutputProbe.Operation;
import com.logyard4j.logyard.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.OutputStream;
import java.time.Duration;

final class JsonByteHealthConcurrencyTest {
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
        OutputStream output = new OutputStream() {
            @Override
            public void write(int value) {
                probe.visit(Operation.WRITE);
            }

            @Override
            public void write(byte[] value, int offset, int length) {
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
        JsonLinesSink sink = JsonLinesSink.bytes(output, new JsonEncoder(ResourceAttributes.service("test", "test", "1")),
                Duration.ofSeconds(1), true, scheduler);
        // Cross the byte-buffer boundary so WRITE stalls in the actual transport.
        probe.verify(sink, scheduler, async, "界".repeat(8_000));
    }
}
