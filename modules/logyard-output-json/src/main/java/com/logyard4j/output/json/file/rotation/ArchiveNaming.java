package com.logyard4j.output.json.file.rotation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact archive grammar; lookalike files are never considered Logyard-owned. */
public final class ArchiveNaming {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final int MAX_SEQUENCE = 999_999;

    private final Path activePath;
    private final String stem;
    private final String extension;
    private final Pattern archivePattern;
    private final Clock clock;

    public ArchiveNaming(Path activePath) {
        this(activePath, Clock.systemUTC());
    }

    ArchiveNaming(Path activePath, Clock clock) {
        this.activePath = Objects.requireNonNull(activePath, "activePath").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        String filename = this.activePath.getFileName().toString();
        int extensionIndex = filename.lastIndexOf('.');
        if (extensionIndex <= 0) {
            stem = filename;
            extension = "";
        } else {
            stem = filename.substring(0, extensionIndex);
            extension = filename.substring(extensionIndex);
        }
        archivePattern = Pattern.compile(
                Pattern.quote(stem)
                        + "\\.(\\d{8}T\\d{6}\\.\\d{3}Z)\\.(\\d{6})"
                        + Pattern.quote(extension)
                        + "(\\.gz)?");
    }

    public Path nextArchive() {
        String timestamp = TIMESTAMP.format(Instant.now(clock));
        Path directory = directory();
        for (int sequence = 0; sequence <= MAX_SEQUENCE; sequence++) {
            String candidateName = stem + "." + timestamp + "."
                    + String.format(java.util.Locale.ROOT, "%06d", sequence) + extension;
            Path candidate = directory.resolve(candidateName);
            if (!Files.exists(candidate)
                    && !Files.exists(gzipPath(candidate))
                    && !Files.exists(gzipTemporaryPath(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("exhausted Logyard archive sequence for " + activePath);
    }

    public Optional<ArchiveIdentity> recognize(Path candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!candidate.toAbsolutePath().normalize().getParent().equals(directory())) {
            return Optional.empty();
        }
        Matcher matcher = archivePattern.matcher(candidate.getFileName().toString());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String key = matcher.group(1) + "." + matcher.group(2);
        return Optional.of(new ArchiveIdentity(candidate, key, matcher.group(3) != null));
    }

    public boolean isGzipTemporary(Path candidate) {
        String name = candidate.getFileName().toString();
        if (!name.endsWith(".gz.tmp")) {
            return false;
        }
        String finalName = name.substring(0, name.length() - ".tmp".length());
        return recognize(candidate.resolveSibling(finalName)).map(ArchiveIdentity::compressed).orElse(false);
    }

    public Path gzipPath(Path archive) {
        return gzipPathStatic(archive);
    }

    public Path gzipTemporaryPath(Path archive) {
        return gzipTemporaryPathStatic(archive);
    }

    public Path directory() {
        Path parent = activePath.getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }

    public static Comparator<ArchiveIdentity> chronologicalOrder() {
        return Comparator.comparing(ArchiveIdentity::key)
                .thenComparing(identity -> identity.path().getFileName().toString());
    }

    private static Path gzipPathStatic(Path archive) {
        return archive.resolveSibling(archive.getFileName() + ".gz");
    }

    private static Path gzipTemporaryPathStatic(Path archive) {
        return archive.resolveSibling(archive.getFileName() + ".gz.tmp");
    }

    /** Parsed identity used for deterministic retention. */
    public record ArchiveIdentity(Path path, String key, boolean compressed) {
        public ArchiveIdentity {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(key, "key");
        }
    }
}
