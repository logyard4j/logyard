package com.logyard4j.runtime.tools;

/** Conversion fidelity is independent of whether a generated document validates. */
enum MigrationOutcome {
    EXACT,
    LOSSY,
    UNSUPPORTED
}
