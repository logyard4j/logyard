package com.zsumz.logyard.output.json.file.lease;

import com.zsumz.logyard.api.lifecycle.CloseLifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** JVM-local ownership registry that closes the hard-link gap left by sidecar path locks. */
final class ActiveFileIdentityRegistry {
    private static final Set<Path> OWNED_PATHS = new LinkedHashSet<>();

    private ActiveFileIdentityRegistry() {
    }

    static synchronized Registration claim(Path path) throws IOException {
        for (Path owned : OWNED_PATHS) {
            if (path.equals(owned) || existingPathsReferToSameFile(path, owned)) {
                throw new FileLeaseUnavailableException(path, true, null);
            }
        }
        OWNED_PATHS.add(path);
        return new Registration(path);
    }

    private static boolean existingPathsReferToSameFile(Path left, Path right) throws IOException {
        return Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
    }

    static final class Registration implements AutoCloseable {
        private final Path path;
        private final CloseLifecycle lifecycle = new CloseLifecycle();

        private Registration(Path path) {
            this.path = path;
        }

        @Override
        public void close() {
            synchronized (ActiveFileIdentityRegistry.class) {
                if (lifecycle.beginClose()) {
                    OWNED_PATHS.remove(path);
                }
            }
        }
    }
}
