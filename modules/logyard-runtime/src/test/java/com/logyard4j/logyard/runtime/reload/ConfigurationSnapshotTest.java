package com.logyard4j.logyard.runtime.reload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ConfigurationSnapshotTest {
    @Test
    void rejectsMalformedUtf8() throws Exception {
        Path source = Files.createTempFile("logyard-invalid-utf8-", ".toml");
        Files.write(source, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, () -> ConfigurationSnapshot.read(source));
    }

    @Test
    void sameContentHasStableDigest() throws Exception {
        Path source = Files.createTempFile("logyard-digest-", ".toml");
        Files.writeString(source, "version = 1\n", StandardCharsets.UTF_8);
        ConfigurationSnapshot first = ConfigurationSnapshot.read(source);
        ConfigurationSnapshot second = ConfigurationSnapshot.read(source);
        assertTrue(first.sameContent(second));
    }

    @Test
    void changedContentChangesDigest() throws Exception {
        Path source = Files.createTempFile("logyard-digest-change-", ".toml");
        Files.writeString(source, "version = 1\n", StandardCharsets.UTF_8);
        ConfigurationSnapshot first = ConfigurationSnapshot.read(source);
        Files.writeString(source, "version = 2\n", StandardCharsets.UTF_8);
        assertFalse(first.sameContent(ConfigurationSnapshot.read(source)));
    }
}
