/**
 * Native scoped logging context carried into every event captured on the pushing thread.
 *
 * <p>Context is bound with {@link com.zsumz.logyard.api.context.LogContext#push} and released by
 * closing the returned {@link com.zsumz.logyard.api.context.ContextScope}, normally through
 * try-with-resources. Snapshots are immutable and pre-merged at push time; there is no per-key
 * mutation and no per-event merge cost while a thread has no context bound.</p>
 *
 * <p>These contracts are deliberately scope-shaped rather than map-shaped so the underlying carrier
 * can change without changing this API. Attributes captured by the {@code [context]} configuration
 * section and {@link com.zsumz.logyard.api.spi.context.ContextProvider} implementations remain a
 * separate, later stage of the pipeline.</p>
 */
package com.zsumz.logyard.api.context;
