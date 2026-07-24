package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Process-wide state machine for runtime identity, configuration handoff, and ownership. */
public final class RuntimeInstallationManager {
    private static final RuntimeInstallationManager PROCESS =
            new RuntimeInstallationManager(new LogyardGlobalRuntimeAccess(), new InstallationShutdownHook(), System::getenv);

    private final GlobalRuntimeAccess globalRuntime;
    private final RuntimeShutdownHookRegistrar shutdownHooks;
    private final Supplier<Map<String, String>> environment;
    private final RuntimeInstallationFactory installations;
    private final RuntimeInstallationRetirements retirements = new RuntimeInstallationRetirements();
    private final RuntimeLeaseCounts leaseCounts = new RuntimeLeaseCounts();

    private InstallationPhase phase = InstallationPhase.EMPTY;
    private RuntimeInstallation installation;
    private volatile RuntimeInstallation publishedInstallation;
    private RuntimeOwner configurationAuthority;
    private long generation;
    private boolean shutdownHookInstalled;
    private boolean terminating;

    RuntimeInstallationManager(GlobalRuntimeAccess globalRuntime, RuntimeShutdownHookRegistrar shutdownHooks, Supplier<Map<String, String>> environment) {
        this(globalRuntime, shutdownHooks, environment, ManagedRuntimeInstallation::open);
    }

    RuntimeInstallationManager(GlobalRuntimeAccess globalRuntime, RuntimeShutdownHookRegistrar shutdownHooks,
            Supplier<Map<String, String>> environment, RuntimeInstallationFactory installations) {
        this.globalRuntime = Objects.requireNonNull(globalRuntime, "globalRuntime");
        this.shutdownHooks = Objects.requireNonNull(shutdownHooks, "shutdownHooks");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.installations = Objects.requireNonNull(installations, "installations");
    }

    public static RuntimeInstallationManager process() {
        return PROCESS;
    }

    public RuntimeInstallationLease acquireApplication(ConfigurationInstallationRequest request) {
        return acquire(RuntimeOwner.APPLICATION, request);
    }

    public RuntimeInstallationLease acquireFramework(ConfigurationInstallationRequest request) {
        return acquire(RuntimeOwner.FRAMEWORK, request);
    }

    public RuntimeInstallationLease acquireAdapter(ConfigurationInstallationRequest request) {
        return acquire(RuntimeOwner.ADAPTER, request);
    }

    boolean isActive(RuntimeInstallation candidate) {
        return publishedInstallation == candidate;
    }

    synchronized int leaseCount(RuntimeOwner owner) {
        return leaseCounts.get(owner);
    }

    void release(RuntimeOwner owner, RuntimeInstallation candidate) {
        RuntimeInstallation closing = null;
        long closingGeneration = 0;
        synchronized (this) {
            if (installation != candidate || phase == InstallationPhase.CLOSING || phase == InstallationPhase.TERMINATED) {
                return;
            }
            leaseCounts.decrement(owner);
            if (leaseCounts.total() == 0) {
                phase = InstallationPhase.CLOSING;
                publishedInstallation = null;
                configurationAuthority = null;
                closing = candidate;
                closingGeneration = ++generation;
            }
        }
        if (closing != null) {
            beginClosing(closing, closingGeneration);
        }
    }

    private RuntimeInstallationLease acquire(RuntimeOwner owner, ConfigurationInstallationRequest request) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(request, "request");
        AcquisitionPlan plan;
        synchronized (this) {
            plan = reserveAcquisition(owner, globalRuntime.current());
        }
        return switch (plan.action()) {
            case BORROW -> RuntimeInstallationLease.borrowed(globalRuntime, plan.borrowedRuntime());
            case SHARE -> RuntimeInstallationLease.managed(this, globalRuntime, owner, plan.installation());
            case START -> start(owner, request, plan);
            case RECONFIGURE -> reconfigure(owner, request, plan);
            case CLOSE_STALE -> {
                beginClosing(plan.installation(), plan.generation());
                throw InstallationTransitionFailures.forPhase(InstallationPhase.CLOSING);
            }
        };
    }

    private AcquisitionPlan reserveAcquisition(RuntimeOwner owner, LogyardRuntime observedGlobal) {
        if (phase == InstallationPhase.STARTING || phase == InstallationPhase.RECONFIGURING
                || phase == InstallationPhase.CLOSING || phase == InstallationPhase.TERMINATED) {
            throw InstallationTransitionFailures.forPhase(phase);
        }
        if (phase == InstallationPhase.ACTIVE) {
            if (observedGlobal != installation.runtime()) {
                phase = InstallationPhase.CLOSING;
                publishedInstallation = null;
                leaseCounts.clear();
                configurationAuthority = null;
                return AcquisitionPlan.closeStale(installation, ++generation);
            }
            if (owner.canReplace(configurationAuthority)) {
                phase = InstallationPhase.RECONFIGURING;
                leaseCounts.increment(owner);
                return AcquisitionPlan.reconfigure(installation, ++generation);
            }
            leaseCounts.increment(owner);
            return AcquisitionPlan.shared(installation);
        }
        if (observedGlobal != null) {
            if (owner == RuntimeOwner.ADAPTER) {
                return AcquisitionPlan.borrowed(observedGlobal);
            }
            throw new IllegalStateException("Logyard is already initialized outside the runtime installation manager");
        }
        phase = InstallationPhase.STARTING;
        return AcquisitionPlan.start(++generation, !shutdownHookInstalled);
    }

    private RuntimeInstallationLease start(
            RuntimeOwner owner,
            ConfigurationInstallationRequest request,
            AcquisitionPlan plan) {
        RuntimeInstallation candidate = null;
        try {
            candidate = installations.open(request, Map.copyOf(environment.get()));
            RuntimeInstallation starting = candidate;
            synchronized (this) {
                if (phase != InstallationPhase.STARTING || generation != plan.generation()) {
                    throw InstallationTransitionFailures.forPhase(phase);
                }
                installation = candidate;
            }
            globalRuntime.install(candidate.runtime(), () -> shutdownManaged(starting));
            if (plan.installShutdownHook()) {
                boolean installed = shutdownHooks.install(this::shutdownAtExit);
                if (installed) {
                    synchronized (this) {
                        shutdownHookInstalled = true;
                    }
                }
            }
            synchronized (this) {
                if (phase != InstallationPhase.STARTING || generation != plan.generation()) {
                    throw InstallationTransitionFailures.forPhase(phase);
                }
                installation = candidate;
                configurationAuthority = owner;
                leaseCounts.increment(owner);
                phase = InstallationPhase.ACTIVE;
                publishedInstallation = candidate;
            }
            return RuntimeInstallationLease.managed(this, globalRuntime, owner, candidate);
        } catch (RuntimeException | Error failure) {
            failStart(candidate, plan.generation(), failure);
            throw failure;
        }
    }

    private RuntimeInstallationLease reconfigure(
            RuntimeOwner owner,
            ConfigurationInstallationRequest request,
            AcquisitionPlan plan) {
        try {
            ReloadResult result = Objects.requireNonNull(plan.installation().reconfigure(request), "runtime reconfiguration result");
            if (result == ReloadResult.REJECTED) {
                throw InstallationTransitionFailures.rejectedHandoff();
            }
            synchronized (this) {
                if (phase != InstallationPhase.RECONFIGURING || generation != plan.generation()) {
                    throw InstallationTransitionFailures.forPhase(phase);
                }
                configurationAuthority = owner;
                phase = InstallationPhase.ACTIVE;
            }
            return RuntimeInstallationLease.managed(this, globalRuntime, owner, plan.installation());
        } catch (RuntimeException | Error failure) {
            RuntimeInstallation closing = null;
            long closingGeneration = 0;
            synchronized (this) {
                if (phase == InstallationPhase.RECONFIGURING && generation == plan.generation()) {
                    leaseCounts.decrement(owner);
                    if (leaseCounts.total() == 0) {
                        phase = InstallationPhase.CLOSING;
                        publishedInstallation = null;
                        configurationAuthority = null;
                        closing = plan.installation();
                        closingGeneration = ++generation;
                    } else {
                        phase = InstallationPhase.ACTIVE;
                    }
                }
            }
            if (closing != null) {
                beginClosing(closing, closingGeneration);
            }
            throw failure;
        }
    }

    private void failStart(RuntimeInstallation candidate, long expectedGeneration, Throwable primaryFailure) {
        RuntimeInstallation closing = null;
        long closingGeneration = 0;
        synchronized (this) {
            if (phase != InstallationPhase.STARTING || generation != expectedGeneration) {
                return;
            }
            if (candidate == null) {
                installation = null;
                phase = terminating ? InstallationPhase.TERMINATED : InstallationPhase.EMPTY;
            } else {
                installation = candidate;
                publishedInstallation = null;
                leaseCounts.clear();
                configurationAuthority = null;
                phase = InstallationPhase.CLOSING;
                closing = candidate;
                closingGeneration = ++generation;
            }
        }
        if (closing != null) {
            beginClosing(closing, closingGeneration).whenComplete((ignored, cleanupFailure) -> {
                if (cleanupFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
            });
        }
    }

    private void shutdownAtExit() {
        requestShutdown(null, true);
    }

    private CompletionStage<Void> beginClosing(RuntimeInstallation candidate, long expectedGeneration) {
        return retirements.close(candidate, globalRuntime, failure -> {
            synchronized (this) {
                if (phase == InstallationPhase.CLOSING && generation == expectedGeneration && installation == candidate) {
                    installation = null;
                    phase = terminating ? InstallationPhase.TERMINATED : InstallationPhase.EMPTY;
                }
            }
        });
    }

    private boolean shutdownManaged(RuntimeInstallation candidate) {
        return requestShutdown(candidate, false);
    }

    private boolean requestShutdown(RuntimeInstallation expected, boolean terminateProcess) {
        RuntimeInstallation closing;
        long closingGeneration;
        synchronized (this) {
            if (terminateProcess) {
                terminating = true;
            }
            if (expected != null && installation != expected) {
                return false;
            }
            if (phase == InstallationPhase.CLOSING) {
                return true;
            }
            if (phase == InstallationPhase.TERMINATED) {
                return false;
            }
            if (phase == InstallationPhase.EMPTY) {
                phase = terminating ? InstallationPhase.TERMINATED : InstallationPhase.EMPTY;
                return terminateProcess;
            }
            phase = InstallationPhase.CLOSING;
            publishedInstallation = null;
            leaseCounts.clear();
            configurationAuthority = null;
            closing = installation;
            closingGeneration = ++generation;
        }
        if (closing != null) {
            beginClosing(closing, closingGeneration);
        }
        return true;
    }
}
