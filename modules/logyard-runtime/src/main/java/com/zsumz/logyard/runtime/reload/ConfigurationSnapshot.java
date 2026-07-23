package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.LogyardConfigLoader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Bounded, strict-UTF-8 source snapshot identified by a SHA-256 content digest. */
public final class ConfigurationSnapshot {
    private final Path source;
    private final String text;
    private final String sha256;

    private ConfigurationSnapshot(Path source, String text, String sha256) {
        this.source = source;
        this.text = text;
        this.sha256 = sha256;
    }

    public static ConfigurationSnapshot read(Path source) throws IOException {
        Path normalized = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Logyard configuration is not a regular file: " + normalized);
        }
        long declaredSize = Files.size(normalized);
        if (declaredSize > LogyardConfigLoader.MAX_CONFIG_BYTES) {
            throw new IOException("Logyard configuration exceeds " + LogyardConfigLoader.MAX_CONFIG_BYTES
                    + " bytes: " + normalized);
        }
        byte[] bytes = Files.readAllBytes(normalized);
        if (bytes.length > LogyardConfigLoader.MAX_CONFIG_BYTES) {
            throw new IOException("Logyard configuration grew beyond " + LogyardConfigLoader.MAX_CONFIG_BYTES
                    + " bytes while reading: " + normalized);
        }
        String text = decodeStrictUtf8(bytes, normalized);
        return new ConfigurationSnapshot(normalized, text, sha256(bytes));
    }

    public LogyardConfig parse(Map<String, String> environment) {
        Path parent = source.getParent();
        Path base = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
        return LogyardConfigLoader.parse(text, source.toString(), base, environment);
    }

    public Path source() {
        return source;
    }

    public String sha256() {
        return sha256;
    }

    public boolean sameContent(ConfigurationSnapshot other) {
        return other != null && sha256.equals(other.sha256);
    }

    private static String decodeStrictUtf8(byte[] bytes, Path source) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("Logyard configuration is not valid UTF-8: " + source, malformed);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
