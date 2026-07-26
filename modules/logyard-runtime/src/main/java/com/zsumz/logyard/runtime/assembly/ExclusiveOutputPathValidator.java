package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.config.output.OutputConfig;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Rejects candidate outputs that require the same normalized exclusive resource. */
final class ExclusiveOutputPathValidator {
    private ExclusiveOutputPathValidator() {
    }

    static void validate(Iterable<OutputConfig> outputs) {
        Map<Path, String> owners = new LinkedHashMap<>();
        for (OutputConfig output : outputs) {
            Path path = OutputSignatures.exclusivePath(output);
            if (path == null) {
                continue;
            }
            String existing = owners.putIfAbsent(path, output.name());
            if (existing != null) {
                throw new IllegalArgumentException(
                        "outputs '" + existing + "' and '" + output.name()
                                + "' both require exclusive access to " + path);
            }
        }
    }
}
