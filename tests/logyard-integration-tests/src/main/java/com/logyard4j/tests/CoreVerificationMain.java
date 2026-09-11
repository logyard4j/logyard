package com.logyard4j.tests;

import com.logyard4j.tests.verification.VerificationSuite;
import com.logyard4j.tests.verification.EcsFixtures;

import java.nio.file.Path;

/** Dependency-free integration checks for Logyard's core logging path. */
public final class CoreVerificationMain {
    private CoreVerificationMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 2 && "--ecs-fixtures".equals(arguments[0])) {
            EcsFixtures.write(Path.of(arguments[1]));
            return;
        }
        if (arguments.length != 0) {
            throw new IllegalArgumentException("usage: CoreVerificationMain [--ecs-fixtures DIRECTORY]");
        }
        VerificationSuite.core().verify(System.out);
    }
}
