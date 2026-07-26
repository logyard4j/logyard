package com.zsumz.logyard.output.json.flush;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlushDiagnosticsTest {
    @Test
    void repeatedDispatchFailuresAreRateLimited() {
        FlushDiagnostics diagnostics = new FlushDiagnostics.StderrFlushDiagnostics();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            diagnostics.report(new IllegalStateException("first"));
            diagnostics.report(new IllegalStateException("second"));
        } finally {
            System.setErr(original);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertEquals(1L, output.lines().count());
        assertTrue(output.contains("first"));
    }
}
