package com.logyard4j.logyard.runtime.assembly.output;

import com.logyard4j.logyard.config.output.OutputConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Rejects candidate outputs that require the same normalized path or existing file identity. */
public final class ExclusiveOutputPathValidator {
    private ExclusiveOutputPathValidator() {
    }

    static void validate(Iterable<OutputConfig> outputs) {
        List<Owner> owners = new ArrayList<>();
        for (OutputConfig output : outputs) {
            Path path = OutputSignatures.exclusivePath(output);
            if (path == null) {
                continue;
            }
            for (Owner owner : owners) {
                if (refersToSameFile(path, owner.path())) {
                    throw new IllegalArgumentException(
                            "outputs '" + owner.name() + "' (" + owner.path() + ") and '" + output.name() + "' ("
                                    + path + ") both require exclusive access to the same file");
                }
            }
            owners.add(new Owner(output.name(), path));
        }
    }

    public static boolean refersToSameFile(Path left, Path right) {
        Path normalizedLeft = left.toAbsolutePath().normalize();
        Path normalizedRight = right.toAbsolutePath().normalize();
        if (normalizedLeft.equals(normalizedRight)) {
            return true;
        }
        try {
            return Files.exists(normalizedLeft) && Files.exists(normalizedRight)
                    && Files.isSameFile(normalizedLeft, normalizedRight);
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "failed to compare exclusive output paths " + normalizedLeft + " and " + normalizedRight,
                    failure);
        }
    }

    private record Owner(String name, Path path) {
    }
}
