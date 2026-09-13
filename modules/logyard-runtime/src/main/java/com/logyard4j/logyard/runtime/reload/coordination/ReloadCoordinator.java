package com.logyard4j.logyard.runtime.reload.coordination;

import com.logyard4j.logyard.api.reload.ReloadResult;
import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.logyard.runtime.assembly.RuntimeAssembly;
import com.logyard4j.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.logyard.runtime.reload.ConfigurationSnapshot;
import com.logyard4j.logyard.runtime.reload.ConfigurationInputs;
import com.logyard4j.logyard.runtime.reload.ConfigurationSnapshotReader;
import com.logyard4j.logyard.runtime.reload.WatcherReloadOutcome;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Coordinates a generation-checked, two-phase reload without holding a monitor across extension or I/O work. */
public final class ReloadCoordinator {
    private final String sourceDescription;
    private final Path legacyFileSource;
    private final DefaultLogyardRuntime runtime;
    private final RuntimePlanPublisher planPublisher;
    private final ReloadDiagnosticBoundary diagnostics;
    private final ReloadCandidatePreparer candidates;
    private final ReloadState state;
    private final RejectedSnapshotMemo rejectedSnapshots = new RejectedSnapshotMemo();

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
        this(
                sourceDescription,
                legacyFileSource,
                snapshotReader,
                runtime,
                runtime::reload,
                snapshot,
                assembly,
                diagnostics,
                ConfigurationInputs.capture(environment));
    }

    public ReloadCoordinator(
            String sourceDescription,
            Path legacyFileSource,
            ConfigurationSnapshotReader snapshotReader,
            DefaultLogyardRuntime runtime,
            ConfigurationSnapshot snapshot,
            RuntimeAssembly assembly,
            ReloadDiagnostics diagnostics,
            ConfigurationInputs inputs) {
        this(
                sourceDescription,
                legacyFileSource,
                snapshotReader,
                runtime,
                runtime::reload,
                snapshot,
                assembly,
                diagnostics,
                inputs);
    }

    ReloadCoordinator(
            String sourceDescription,
            Path legacyFileSource,
            ConfigurationSnapshotReader snapshotReader,
            DefaultLogyardRuntime runtime,
            RuntimePlanPublisher planPublisher,
            ConfigurationSnapshot snapshot,
            RuntimeAssembly assembly,
            ReloadDiagnostics diagnostics,
            ConfigurationInputs inputs) {
        this.sourceDescription = Objects.requireNonNull(sourceDescription, "sourceDescription");
        this.legacyFileSource = legacyFileSource == null ? null : legacyFileSource.toAbsolutePath().normalize();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.planPublisher = Objects.requireNonNull(planPublisher, "planPublisher");
        this.diagnostics = new ReloadDiagnosticBoundary(diagnostics);
        candidates = new ReloadCandidatePreparer(snapshotReader, inputs);
        state = new ReloadState(snapshot, assembly);
    }

    public ReloadResult reloadIfChanged() {
        return reload(false).publicResult();
    }

    /** Reloads for a watcher while preserving whether a rejection is transient or candidate-specific. */
    public WatcherReloadOutcome reloadForWatcher() {
        return reload(true);
    }

    private WatcherReloadOutcome reload(boolean suppressKnownRejection) {
        ReloadState.Reservation reservation = state.tryReserve();
        if (reservation == null) {
            return WatcherReloadOutcome.BUSY_RETRY;
        }

        ReloadCompletion completion;
        try {
            completion = execute(reservation, suppressKnownRejection);
        } finally {
            state.release(reservation);
        }
        completion.notify(diagnostics, sourceDescription, legacyFileSource);
        return completion.outcome();
    }

    private ReloadCompletion execute(ReloadState.Reservation reservation, boolean suppressKnownRejection) {
        ReloadState.ActiveConfiguration active = reservation.active();
        ReloadCandidatePreparer.SnapshotRead read = candidates.read();
        if (!read.succeeded()) {
            return ReloadCompletion.rejected(read.failure());
        }

        ConfigurationSnapshot snapshot = read.snapshot();
        if (snapshot.sameContent(active.snapshot())) {
            rejectedSnapshots.clear();
            return ReloadCompletion.unchanged(snapshot.sha256());
        }
        if (suppressKnownRejection && rejectedSnapshots.contains(snapshot.sha256())) {
            return ReloadCompletion.silent(WatcherReloadOutcome.INVALID_CANDIDATE);
        }

        ReloadCandidatePreparer.CandidatePreparation prepared = candidates.prepare(snapshot, active.assembly());
        if (!prepared.succeeded()) {
            return reject(snapshot, prepared.failure());
        }
        return publish(reservation, active, snapshot, prepared.assembly());
    }

    private ReloadCompletion publish(
            ReloadState.Reservation reservation,
            ReloadState.ActiveConfiguration active,
            ConfigurationSnapshot snapshot,
            RuntimeAssembly candidate) {
        try {
            candidate.activateCandidateOutputs();
            planPublisher.publish(candidate.plan());
        } catch (RuntimeException failure) {
            candidate.closeCandidateOutputs(active.assembly(), failure);
            return reject(snapshot, ReloadFailureClassifier.publication(failure));
        }

        if (!state.commit(reservation, snapshot, candidate)) {
            IllegalStateException failure = new IllegalStateException("reload generation changed while its writer reservation was held");
            rollbackPublication(active, candidate, failure);
            return reject(snapshot, new ReloadFailure(ReloadFailureKind.INTERNAL_FAILURE, failure));
        }

        LogyardRuntimeFactory.attach(runtime, candidate);
        rejectedSnapshots.clear();
        return ReloadCompletion.applied(active.snapshot().sha256(), snapshot.sha256());
    }

    private void rollbackPublication(
            ReloadState.ActiveConfiguration active,
            RuntimeAssembly candidate,
            IllegalStateException failure) {
        try {
            planPublisher.publish(active.assembly().plan());
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        candidate.closeCandidateOutputs(active.assembly(), failure);
    }

    private ReloadCompletion reject(ConfigurationSnapshot snapshot, ReloadFailure failure) {
        rejectedSnapshots.record(snapshot.sha256(), failure.kind());
        return ReloadCompletion.rejected(failure);
    }

    public LogyardConfig currentConfig() {
        return state.current().assembly().config();
    }

    public RuntimeAssembly currentAssembly() {
        return state.current().assembly();
    }

    public String currentDigest() {
        return state.current().snapshot().sha256();
    }
}
