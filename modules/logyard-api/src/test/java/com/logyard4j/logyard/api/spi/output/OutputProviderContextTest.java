package com.logyard4j.logyard.api.spi.output;

import com.logyard4j.logyard.api.event.AttributeSet;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OutputProviderContextTest {
    @Test
    void oversizedNamesProduceBoundedDiagnostics() {
        String oversized = " " + "a".repeat(1_000_000) + " ";
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> context(oversized));

        assertTrue(failure.getMessage().length() <= 128, "oversized output names must not expand diagnostics");
    }

    @Test
    void exactNameLimitRemainsSupportedWithLargeWhitespacePadding() {
        String name = "a".repeat(64);
        String padding = " \t\r\n".repeat(1_000);
        OutputProviderContext context = context(padding + name + padding);

        assertEquals(name, context.outputName());
        assertEquals(AttributeSet.EMPTY, context.resourceAttributes());
        assertEquals(Duration.ZERO, context.shutdownTimeout());
        assertEquals("Audit_2.json-file", context(" Audit_2.json-file ").outputName());
    }

    @Test
    void invalidNameSyntaxAndExcessLengthRemainRejected() {
        for (String name : new String[] {"", " \t\r\n", "1output", "bad name", "file/child", "\u2003output", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> context(name));
        }
    }

    private static OutputProviderContext context(String name) {
        return new OutputProviderContext(name, AttributeSet.EMPTY, Duration.ZERO, null, null);
    }
}
