package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Serializes parse, assembly, publication, registry update, and rollback. */
public final class ReloadCoordinator {
    private final Path source;
    private final DefaultLogyardRuntime runtime;
    private final ReloadDiagnosticBoundary diagnostics;
    private final Map<String, String> environment;
    private ConfigurationSnapshot snapshot;
    private RuntimeAssembly assembly;

    public ReloadCoordinator(
            Path source,
            DefaultLogyardRuntime runtime,
            ConfigurationSnapshot snapshot,
            RuntimeAssembly assembly,
            ReloadDiagnostics diagnostics,
            Map<String, String> environment) {
        this.source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.diagnostics = new ReloadDiagnosticBoundary(diagnostics);
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    public synchronized ReloadResult reloadIfChanged() {
        ConfigurationSnapshot candidateSnapshot;
        try {
            candidateSnapshot = ConfigurationSnapshot.read(source);
        } catch (IOException | RuntimeException readFailure) {
            diagnostics.rejected(source, readFailure);
            return ReloadResult.REJECTED;
        }
        if (candidateSnapshot.sameContent(snapshot)) {
            diagnostics.unchanged(source, snapshot.sha256());
            return ReloadResult.UNCHANGED;
        }

        RuntimeAssembly candidate = null;
        try {
            LogyardConfig candidateConfig = candidateSnapshot.parse(environment);
            candidate = LogyardRuntimeFactory.assemble(candidateConfig, assembly);
            runtime.reload(candidate.plan());
            String previousDigest = snapshot.sha256();
            assembly = candidate;
            snapshot = candidateSnapshot;
            LogyardRuntimeFactory.attach(runtime, candidate);
            diagnostics.applied(source, previousDigest, candidateSnapshot.sha256());
            return ReloadResult.APPLIED;
        } catch (RuntimeException reloadFailure) {
            if (candidate != null) {
                candidate.closeCandidateOutputs(assembly, reloadFailure);
            }
            diagnostics.rejected(source, reloadFailure);
            return ReloadResult.REJECTED;
        }
    }

    public synchronized LogyardConfig currentConfig() {
        return assembly.config();
    }

    public synchronized RuntimeAssembly currentAssembly() {
        return assembly;
    }

    public synchronized String currentDigest() {
        return snapshot.sha256();
    }
}
