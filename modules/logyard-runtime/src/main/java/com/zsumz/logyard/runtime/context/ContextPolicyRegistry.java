package com.zsumz.logyard.runtime.context;

import com.zsumz.logyard.api.LogyardRuntime;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Control-plane registry of per-runtime context policy publishers. */
public final class ContextPolicyRegistry {
    private static final Map<LogyardRuntime, PolicyReference> POLICIES = Collections.synchronizedMap(new WeakHashMap<>());

    private ContextPolicyRegistry() {
    }

    public static void publish(LogyardRuntime runtime, ContextPolicySnapshot policy) {
        referenceFor(runtime).publish(Objects.requireNonNull(policy, "policy"));
    }

    public static Supplier<ContextPolicySnapshot> sourceFor(LogyardRuntime runtime) {
        return referenceFor(runtime)::current;
    }

    private static PolicyReference referenceFor(LogyardRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        synchronized (POLICIES) {
            return POLICIES.computeIfAbsent(runtime, ignored -> new PolicyReference());
        }
    }

    private static final class PolicyReference {
        private volatile ContextPolicySnapshot current = ContextPolicySnapshot.none();

        void publish(ContextPolicySnapshot next) {
            current = next;
        }

        ContextPolicySnapshot current() {
            return current;
        }
    }
}
