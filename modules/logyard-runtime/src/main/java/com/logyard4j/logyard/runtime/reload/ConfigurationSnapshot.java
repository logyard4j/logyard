package com.logyard4j.logyard.runtime.reload;

import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.config.loading.source.BoundedConfigurationFile;
import com.logyard4j.logyard.config.loading.LogyardConfigLoader;
import com.logyard4j.logyard.config.loading.overlay.ConfigOverlays;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Bounded, strict-UTF-8 source snapshot identified by a SHA-256 content digest. */
public final class ConfigurationSnapshot {
    private final String description;
    private final Path baseDirectory;
    private final Path watchPath;
    private final String text;
    private final String sha256;

    private ConfigurationSnapshot(
            String description,
            Path baseDirectory,
            Path watchPath,
            String text,
            String sha256) {
        this.description = description;
        this.baseDirectory = baseDirectory;
        this.watchPath = watchPath;
        this.text = text;
        this.sha256 = sha256;
    }

    public static ConfigurationSnapshot read(Path source) throws IOException {
        Path normalized = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        byte[] bytes = BoundedConfigurationFile.read(normalized);
        Path parent = normalized.getParent();
        Path baseDirectory = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
        return capture(normalized.toString(), baseDirectory, normalized, bytes);
    }

    public static ConfigurationSnapshot capture(
            String description,
            Path baseDirectory,
            Path watchPath,
            byte[] bytes) throws IOException {
        Objects.requireNonNull(description, "description");
        Path base = Objects.requireNonNull(baseDirectory, "baseDirectory").toAbsolutePath().normalize();
        byte[] content = Objects.requireNonNull(bytes, "bytes");
        if (content.length > LogyardConfigLoader.MAX_CONFIG_BYTES) {
            throw new IOException("Logyard configuration exceeds " + LogyardConfigLoader.MAX_CONFIG_BYTES + " bytes: " + description);
        }
        String text = decodeStrictUtf8(content, description);
        return new ConfigurationSnapshot(
                description,
                base,
                watchPath == null ? null : watchPath.toAbsolutePath().normalize(),
                text,
                sha256(content));
    }

    /** Parses a standalone snapshot with overlays captured at this call. */
    public LogyardConfig parse(Map<String, String> environment) {
        return parse(environment, ConfigOverlays.fromProcess(environment, System.getProperties()));
    }

    /** Parses with explicit immutable overlays; managed installations reuse their launch inputs. */
    public LogyardConfig parse(Map<String, String> environment, ConfigOverlays overlays) {
        return LogyardConfigLoader.parseDetailed(text, description, baseDirectory, environment, overlays).config();
    }

    public Path source() {
        return watchPath;
    }

    public String description() {
        return description;
    }

    public Path watchPath() {
        return watchPath;
    }

    public String sha256() {
        return sha256;
    }

    public boolean sameContent(ConfigurationSnapshot other) {
        return other != null && sha256.equals(other.sha256);
    }

    private static String decodeStrictUtf8(byte[] bytes, String source) throws IOException {
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
