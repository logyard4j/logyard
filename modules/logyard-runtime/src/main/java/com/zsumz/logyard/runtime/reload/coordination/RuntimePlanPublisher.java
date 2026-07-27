package com.zsumz.logyard.runtime.reload.coordination;

import com.zsumz.logyard.core.runtime.RuntimePlan;

/** Publication boundary used to distinguish a valid plan from temporary runtime lifecycle backpressure. */
@FunctionalInterface
interface RuntimePlanPublisher {
    void publish(RuntimePlan plan);
}
