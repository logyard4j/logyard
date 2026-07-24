package com.zsumz.logyard.config.loading;

import com.zsumz.logyard.api.annotation.InternalApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Opens and reads at most one configured limit plus a single overflow byte. */
@InternalApi
public final class BoundedConfigurationFile {
    private BoundedConfigurationFile() {
    }

    /**
     * Reads one regular configuration file without allocating beyond the configured limit.
     *
     * @param source configuration file
     * @return bounded file content
     * @throws IOException when the file is unavailable, invalid, or oversized
     */
    public static byte[] read(Path source) throws IOException {
        Path path = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Logyard configuration is not a regular file: " + path);
        }
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(LogyardConfigLoader.MAX_CONFIG_BYTES + 1);
            if (bytes.length > LogyardConfigLoader.MAX_CONFIG_BYTES) {
                throw new IOException("Logyard configuration exceeds " + LogyardConfigLoader.MAX_CONFIG_BYTES + " bytes: " + path);
            }
            return bytes;
        }
    }
}
