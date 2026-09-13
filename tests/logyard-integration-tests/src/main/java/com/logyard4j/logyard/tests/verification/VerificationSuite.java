package com.logyard4j.logyard.tests.verification;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

/** Runs the dependency-free integration contract shared by local and CI verification. */
public final class VerificationSuite {
    private final List<VerificationCase> cases;

    private VerificationSuite(List<VerificationCase> cases) {
        this.cases = List.copyOf(cases);
    }

    public static VerificationSuite core() {
        return new VerificationSuite(List.of(
                new TemplateAndJsonVerification(),
                new ProcessingBoundsVerification(),
                new ExtensionSpiVerification(),
                new ExtensionGuardrailVerification(),
                new ProcessorInheritanceVerification(),
                new AsyncDeliveryVerification()));
    }

    public void verify(PrintStream output) throws Exception {
        Objects.requireNonNull(output, "output");
        int passed = 0;
        for (VerificationCase verification : cases) {
            verification.verify();
            passed++;
            output.println("PASS  " + verification.description());
        }
        output.println("Core verification passed: " + passed + "/" + cases.size());
    }
}
