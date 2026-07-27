package com.zsumz.logyard.runtime.bootstrap;

import com.zsumz.logyard.config.loading.source.BoundedConfigurationFile;
import com.zsumz.logyard.config.loading.LogyardConfigLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/** Reads bounded configuration bytes for the public source factories. */
final class ConfigurationSourceReader {
    private ConfigurationSourceReader() {
    }

    static byte[] readFile(Path path) throws IOException {
        return BoundedConfigurationFile.read(path);
    }

    static byte[] readClasspath(ClassLoader loader, String resource) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Logyard classpath configuration does not exist: classpath:" + resource);
            }
            byte[] bytes = stream.readNBytes(LogyardConfigLoader.MAX_CONFIG_BYTES + 1);
            if (bytes.length > LogyardConfigLoader.MAX_CONFIG_BYTES) {
                throw new IOException("Logyard classpath configuration exceeds " + LogyardConfigLoader.MAX_CONFIG_BYTES + " bytes: classpath:" + resource);
            }
            return bytes;
        }
    }

    static byte[] encodeText(String toml) {
        String text = Objects.requireNonNull(toml, "toml");
        if (text.length() > LogyardConfigLoader.MAX_CONFIG_BYTES) {
            throw oversizedText();
        }
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        if (content.length > LogyardConfigLoader.MAX_CONFIG_BYTES) {
            throw oversizedText();
        }
        return content;
    }

    private static IllegalArgumentException oversizedText() {
        return new IllegalArgumentException("Logyard text configuration exceeds " + LogyardConfigLoader.MAX_CONFIG_BYTES + " bytes");
    }
}
