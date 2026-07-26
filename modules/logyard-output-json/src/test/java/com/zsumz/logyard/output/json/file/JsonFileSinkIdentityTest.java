package com.zsumz.logyard.output.json.file;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import com.zsumz.logyard.output.json.file.lease.FileLeaseUnavailableException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class JsonFileSinkIdentityTest {
    private static final EventEncoder ENCODER = ignored -> "{}";

    @Test
    void hardLinkAliasesCannotBecomeDirectSinksTogetherAndReleaseAllowsLaterAcquisition() throws Exception {
        Path directory = Files.createTempDirectory("logyard-hard-link-lease-");
        Path firstPath = directory.resolve("first.jsonl");
        Path aliasPath = directory.resolve("alias.jsonl");
        Files.writeString(firstPath, "KEEP-ME\n", StandardCharsets.UTF_8);
        try {
            Files.createLink(aliasPath, firstPath);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        try (JsonFileSink first = prepared(firstPath)) {
            assertEquals(firstPath.toAbsolutePath().normalize(), first.path());
            assertThrows(FileLeaseUnavailableException.class, () -> prepared(aliasPath));
            assertEquals("KEEP-ME\n", Files.readString(firstPath, StandardCharsets.UTF_8));
            assertEquals("KEEP-ME\n", Files.readString(aliasPath, StandardCharsets.UTF_8));
        }

        assertDoesNotThrow(() -> {
            try (JsonFileSink later = prepared(aliasPath)) {
                assertEquals(aliasPath.toAbsolutePath().normalize(), later.path());
            }
        });
    }

    private static JsonFileSink prepared(Path path) {
        return JsonFileSink.prepare(path, ENCODER, 1_024, Duration.ZERO, false, null);
    }
}
