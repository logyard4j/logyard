package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.output.OutputConfig;
import com.zsumz.logyard.runtime.extension.ExtensionRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transactionally assembles configured outputs while reusing only proven-equivalent resources. */
final class OutputAssembler {
    private OutputAssembler() {
    }

    static AssembledOutputs assemble(LogyardConfig config, RuntimeAssembly current, ExtensionRegistry extensions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(extensions, "extensions");
        ExclusiveOutputPathValidator.validate(config.outputs().values());
        Map<String, EventSink> sinks = new LinkedHashMap<>();
        Map<String, OutputBinding> bindings = new LinkedHashMap<>();
        List<OutputPreparation> created = new ArrayList<>();
        try {
            for (OutputConfig output : config.outputs().values()) {
                OutputSignature signature = OutputSignatures.from(config, output);
                Path exclusivePath = OutputSignatures.exclusivePath(output);
                OutputBinding existing = current == null ? null : current.binding(output.name());
                EventSink sink;
                if (reusable(existing, signature)) {
                    sink = existing.sink();
                } else {
                    OutputPreparation preparation = create(config, current, output, exclusivePath, extensions);
                    created.add(preparation);
                    sink = preparation.sink();
                }
                sinks.put(output.name(), sink);
                bindings.put(output.name(), new OutputBinding(sink, signature, exclusivePath));
            }
            return new AssembledOutputs(sinks, bindings, new OutputCandidateSet(created));
        } catch (RuntimeException | Error failure) {
            new OutputCandidateSet(created).close(failure);
            throw failure;
        }
    }

    private static boolean reusable(OutputBinding existing, OutputSignature signature) {
        return existing != null && existing.signature().equals(signature);
    }

    private static OutputPreparation create(
            LogyardConfig config,
            RuntimeAssembly current,
            OutputConfig output,
            Path exclusivePath,
            ExtensionRegistry extensions) {
        verifyExclusivePathAvailable(current, output, exclusivePath);
        return OutputFactory.prepare(config, output, extensions);
    }

    private static void verifyExclusivePathAvailable(RuntimeAssembly current, OutputConfig output, Path exclusivePath) {
        if (exclusivePath == null || current == null) {
            return;
        }
        OutputBinding locked = current.bindingForExclusivePath(exclusivePath);
        if (locked != null) {
            throw new IllegalArgumentException(
                    "reload changes file output '" + output.name() + "' at locked path " + exclusivePath
                            + "; restart the process for structural file changes");
        }
    }
}
