package com.zsumz.logyard.runtime.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;
import com.zsumz.logyard.core.failure.ComponentInvocationException;
import com.zsumz.logyard.output.json.file.lease.FileLease;
import com.zsumz.logyard.output.json.file.lease.FileLeaseUnavailableException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ReloadFailureClassifierTest {
    @Test
    void directProviderValidationFailureIsAnInvalidCandidate() {
        ReloadFailure failure =
                ReloadFailureClassifier.assembly(new IllegalArgumentException("invalid provider configuration"));

        assertEquals(ReloadFailureKind.INVALID_CANDIDATE, failure.kind());
    }

    @Test
    void wrappedProviderValidationFailureIsAnInvalidCandidate() {
        ComponentInvocationException wrapped = assertThrows(
                ComponentInvocationException.class,
                () -> ComponentInvocationBoundary.call("provider creation", () -> {
                    throw new IllegalArgumentException("invalid provider configuration");
                }));

        assertEquals(ReloadFailureKind.INVALID_CANDIDATE, ReloadFailureClassifier.assembly(wrapped).kind());
    }

    @Test
    void sameProcessFileLeaseContentionIsTransient() throws Exception {
        Path output = Files.createTempDirectory("logyard-classifier-lease-").resolve("events.jsonl");
        try (FileLease lease = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), lease.activePath());
            FileLeaseUnavailableException unavailable =
                    assertThrows(FileLeaseUnavailableException.class, () -> FileLease.acquire(output));

            assertEquals(ReloadFailureKind.TRANSIENT_RESOURCE, ReloadFailureClassifier.assembly(unavailable).kind());
        }
    }

    @Test
    void wrappedTemporaryIoFailureRemainsTransient() {
        ComponentInvocationException wrapped = assertThrows(
                ComponentInvocationException.class,
                () -> ComponentInvocationBoundary.call("provider creation", () -> {
                    throw new UncheckedIOException(new IOException("temporarily unavailable"));
                }));

        assertEquals(ReloadFailureKind.TRANSIENT_RESOURCE, ReloadFailureClassifier.assembly(wrapped).kind());
    }

    @Test
    void unknownProviderFailureUsesBoundedInternalRetryPolicy() {
        ReloadFailure failure = ReloadFailureClassifier.assembly(new IllegalStateException("provider bug"));

        assertEquals(ReloadFailureKind.INTERNAL_FAILURE, failure.kind());
    }
}
