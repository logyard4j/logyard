package com.logyard4j.runtime.reload.coordination;

import com.logyard4j.core.runtime.RuntimePlan;

/** Publication boundary used to distinguish a valid plan from temporary runtime lifecycle backpressure. */
@FunctionalInterface
interface RuntimePlanPublisher {
    void publish(RuntimePlan plan);
}
