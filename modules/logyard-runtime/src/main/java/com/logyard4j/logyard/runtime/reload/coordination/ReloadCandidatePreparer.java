package com.logyard4j.logyard.runtime.reload.coordination;

import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.logyard.runtime.assembly.RuntimeAssembly;
import com.logyard4j.logyard.runtime.reload.ConfigurationInputs;
import com.logyard4j.logyard.runtime.reload.ConfigurationSnapshot;
import com.logyard4j.logyard.runtime.reload.ConfigurationSnapshotReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/** Performs source, parsing, policy, and assembly work without owning reload state. */
final class ReloadCandidatePreparer {
    private final ConfigurationSnapshotReader snapshotReader;
    private final ConfigurationInputs inputs;

    ReloadCandidatePreparer(ConfigurationSnapshotReader snapshotReader, ConfigurationInputs inputs) {
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
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
            config = inputs.parse(snapshot);
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
