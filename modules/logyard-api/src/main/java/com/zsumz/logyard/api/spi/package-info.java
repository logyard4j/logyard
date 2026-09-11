/**
 * Public extension contracts grouped by the capability they contribute.
 *
 * <p>Providers are discovered through {@link java.util.ServiceLoader}, validated before construction, and owned by one immutable runtime plan. Candidate
 * instances may be constructed without ultimately being published, so construction must not irreversibly mutate durable external state. Only an
 * {@link com.zsumz.logyard.api.spi.output.EventSink} has a managed close callback; context providers, processors, formatters, and encoders must not own
 * resources that require lifecycle cleanup. A provider-created sink must release every acquired resource from {@code close()}.</p>
 *
 * <p>Logyard does not invoke providers while holding its lifecycle or reload state locks. Recursive Logyard installation, reconfiguration, or shutdown from a
 * provider lifecycle callback is unsupported. Implementations should avoid unbounded queues and must not retain runtime-scoped context after close.</p>
 */
package com.zsumz.logyard.api.spi;
