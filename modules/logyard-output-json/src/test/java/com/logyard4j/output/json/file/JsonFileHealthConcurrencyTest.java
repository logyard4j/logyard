package com.logyard4j.output.json.file;

import com.logyard4j.output.json.testing.BlockedOutputProbe;
import com.logyard4j.output.json.testing.BlockedOutputProbe.Operation;
import com.logyard4j.output.json.testing.ManualFlushScheduler;
import com.logyard4j.api.spi.encoding.EventEncoder;
import com.logyard4j.output.json.encoding.JsonEncoder;
import com.logyard4j.output.json.encoding.ResourceAttributes;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

final class JsonFileHealthConcurrencyTest {
    @TempDir
    Path directory;

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

    private void verify(Operation operation, boolean async) throws Exception {
        verify(operation, async, event -> "{}");
        verify(operation, async, new JsonEncoder(new ResourceAttributes(Map.of())));
    }

    private void verify(Operation operation, boolean async, EventEncoder encoder) throws Exception {
        BlockedOutputProbe probe = new BlockedOutputProbe(operation);
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        ActiveDataFile file = new ActiveDataFile() {
            @Override
            public long logicalBytes() {
                return 0;
            }

            @Override
            public void write(byte[] record, int length, byte terminator) {
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
        JsonFileSink sink = new JsonFileSink(directory.resolve("output.jsonl"), encoder, 1_024,
                Duration.ofSeconds(1), false, null, true, scheduler, (path, bytes, append) -> file);
        probe.verify(sink, scheduler, async);
    }
}
