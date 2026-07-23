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
    private final String sourceDescription;
    private final Path legacyFileSource;
    private final ConfigurationSnapshotReader snapshotReader;
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
        this(
                Objects.requireNonNull(source, "source").toAbsolutePath().normalize().toString(),
                source.toAbsolutePath().normalize(),
                () -> ConfigurationSnapshot.read(source),
                runtime,
                snapshot,
                assembly,
                diagnostics,
                environment);
    }

    public ReloadCoordinator(
            String sourceDescription,
            Path legacyFileSource,
            ConfigurationSnapshotReader snapshotReader,
            DefaultLogyardRuntime runtime,
            ConfigurationSnapshot snapshot,
            RuntimeAssembly assembly,
            ReloadDiagnostics diagnostics,
            Map<String, String> environment) {
        this.sourceDescription = Objects.requireNonNull(sourceDescription, "sourceDescription");
        this.legacyFileSource = legacyFileSource == null ? null : legacyFileSource.toAbsolutePath().normalize();
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.diagnostics = new ReloadDiagnosticBoundary(diagnostics);
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    public synchronized ReloadResult reloadIfChanged() {
        ConfigurationSnapshot candidateSnapshot;
        try {
            candidateSnapshot = snapshotReader.read();
        } catch (IOException | RuntimeException readFailure) {
            rejected(readFailure);
            return ReloadResult.REJECTED;
        }
        if (candidateSnapshot.sameContent(snapshot)) {
            unchanged();
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
            applied(previousDigest, candidateSnapshot.sha256());
            return ReloadResult.APPLIED;
        } catch (RuntimeException reloadFailure) {
            if (candidate != null) {
                candidate.closeCandidateOutputs(assembly, reloadFailure);
            }
            rejected(reloadFailure);
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

    private void unchanged() {
        if (legacyFileSource == null) {
            diagnostics.unchanged(sourceDescription, snapshot.sha256());
        } else {
            diagnostics.unchanged(legacyFileSource, snapshot.sha256());
        }
    }

    private void applied(String previousDigest, String nextDigest) {
        if (legacyFileSource == null) {
            diagnostics.applied(sourceDescription, previousDigest, nextDigest);
        } else {
            diagnostics.applied(legacyFileSource, previousDigest, nextDigest);
        }
    }

    private void rejected(Throwable failure) {
        if (legacyFileSource == null) {
            diagnostics.rejected(sourceDescription, failure);
        } else {
            diagnostics.rejected(legacyFileSource, failure);
        }
    }
}
