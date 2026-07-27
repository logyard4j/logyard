package com.zsumz.logyard.runtime.reload.coordination;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshotReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;

/** Performs source, parsing, policy, and assembly work without owning reload state. */
final class ReloadCandidatePreparer {
    private final ConfigurationSnapshotReader snapshotReader;
    private final Map<String, String> environment;

    ReloadCandidatePreparer(ConfigurationSnapshotReader snapshotReader, Map<String, String> environment) {
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    SnapshotRead read() {
        try {
            return SnapshotRead.success(snapshotReader.read());
        } catch (IOException | UncheckedIOException failure) {
            return SnapshotRead.failure(ReloadFailureClassifier.source(failure));
        } catch (RuntimeException failure) {
            return SnapshotRead.failure(ReloadFailureClassifier.source(failure));
        }
    }

    CandidatePreparation prepare(ConfigurationSnapshot snapshot, RuntimeAssembly active) {
        LogyardConfig config;
        try {
            config = snapshot.parse(environment);
        } catch (RuntimeException failure) {
            return CandidatePreparation.failure(ReloadFailureClassifier.parsing(failure));
        }
        try {
            RuntimeReloadPolicy.requireSupportedChanges(active.config(), config);
        } catch (RuntimeException failure) {
            return CandidatePreparation.failure(ReloadFailureClassifier.policy(failure));
        }
        try {
            return CandidatePreparation.success(LogyardRuntimeFactory.assemble(config, active));
        } catch (RuntimeException failure) {
            return CandidatePreparation.failure(ReloadFailureClassifier.assembly(failure));
        }
    }

    record SnapshotRead(ConfigurationSnapshot snapshot, ReloadFailure failure) {
        static SnapshotRead success(ConfigurationSnapshot snapshot) {
            return new SnapshotRead(Objects.requireNonNull(snapshot, "snapshot"), null);
        }

        static SnapshotRead failure(ReloadFailure failure) {
            return new SnapshotRead(null, Objects.requireNonNull(failure, "failure"));
        }

        boolean succeeded() {
            return snapshot != null;
        }
    }

    record CandidatePreparation(RuntimeAssembly assembly, ReloadFailure failure) {
        static CandidatePreparation success(RuntimeAssembly assembly) {
            return new CandidatePreparation(Objects.requireNonNull(assembly, "assembly"), null);
        }

        static CandidatePreparation failure(ReloadFailure failure) {
            return new CandidatePreparation(null, Objects.requireNonNull(failure, "failure"));
        }

        boolean succeeded() {
            return assembly != null;
        }
    }
}
