package com.zsumz.logyard.output.json.file;

import com.zsumz.logyard.api.failure.FailureIsolation;
import com.zsumz.logyard.output.json.file.lease.FileLease;
import com.zsumz.logyard.output.json.file.rotation.ArchiveMaintenance;
import com.zsumz.logyard.output.json.file.rotation.ArchiveNaming;
import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;

import java.nio.file.Path;

/** Orders nondestructive archive preparation before the active file may be opened or truncated. */
final class FileWriterInitialization {
    private final Path path;
    private final int bufferBytes;
    private final boolean append;
    private final RotationPolicy policy;
    private final ArchiveNaming naming;
    private final FileLease lease;
    private final DataFileOpener dataFiles;
    private ArchiveMaintenance maintenance;
    private ActiveDataFile active;

    FileWriterInitialization(
            Path path,
            int bufferBytes,
            boolean append,
            RotationPolicy policy,
            ArchiveNaming naming,
            FileLease lease,
            DataFileOpener dataFiles) {
        this.path = path;
        this.bufferBytes = bufferBytes;
        this.append = append;
        this.policy = policy;
        this.naming = naming;
        this.lease = lease;
        this.dataFiles = dataFiles;
    }

    void initialize() {
        try {
            if (policy != null) {
                maintenance = ArchiveMaintenance.start(naming, policy, lease);
            }
            active = dataFiles.open(path, bufferBytes, append);
        } catch (RuntimeException | Error failure) {
            if (active != null) {
                closeAfterFailure(failure, active::close);
            }
            closeAfterFailure(failure, maintenance == null
                    ? lease::close
                    : maintenance::abortBeforeUse);
            FailureIsolation.prepareForRecovery(failure);
            throw failure;
        }
    }

    ActiveDataFile active() {
        return active;
    }

    ArchiveMaintenance maintenance() {
        return maintenance;
    }

    static void closeAfterFailure(Throwable failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
