package com.logyard4j.core.runtime.management;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Acquires and releases one stable runtime generation while reload and shutdown may race. */
public final class RuntimeGenerationLease implements AutoCloseable {
    private final RuntimeGeneration generation;

    private RuntimeGenerationLease(RuntimeGeneration generation) {
        this.generation = generation;
    }

    public static RuntimeGenerationLease acquire(Supplier<RuntimeGeneration> currentGeneration, BooleanSupplier closed) {
        while (!closed.getAsBoolean()) {
            RuntimeGeneration generation = currentGeneration.get();
            if (generation.epoch().tryAcquire()) {
                return new RuntimeGenerationLease(generation);
            }
        }
        return null;
    }

    public RuntimeGeneration generation() {
        return generation;
    }

    @Override
    public void close() {
        generation.epoch().release();
    }
}
