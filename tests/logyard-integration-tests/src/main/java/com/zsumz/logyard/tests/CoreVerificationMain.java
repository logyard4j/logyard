package com.zsumz.logyard.tests;

import com.zsumz.logyard.tests.verification.VerificationSuite;

/** Dependency-free integration checks for Logyard's core logging path. */
public final class CoreVerificationMain {
    private CoreVerificationMain() {
    }

    public static void main(String[] arguments) throws Exception {
        VerificationSuite.core().verify(System.out);
    }
}
