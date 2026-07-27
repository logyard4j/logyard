package com.zsumz.logyard.runtime.installation.process;

/** Shutdown-hook registration and terminal-process policy for one installation manager. */
final class ProcessShutdownState {
    private ShutdownHookPhase shutdownHookPhase = ShutdownHookPhase.NOT_INSTALLED;
    private TerminationMode terminationMode = TerminationMode.REUSABLE;

    boolean requiresShutdownHook() {
        return shutdownHookPhase == ShutdownHookPhase.NOT_INSTALLED;
    }

    void markShutdownHookInstalled() {
        shutdownHookPhase = ShutdownHookPhase.INSTALLED;
    }

    void markProcessTerminating() {
        terminationMode = TerminationMode.PROCESS_TERMINATING;
    }

    InstallationPhase terminalOrEmptyPhase() {
        return terminationMode == TerminationMode.PROCESS_TERMINATING
                ? InstallationPhase.TERMINATED
                : InstallationPhase.EMPTY;
    }
}
