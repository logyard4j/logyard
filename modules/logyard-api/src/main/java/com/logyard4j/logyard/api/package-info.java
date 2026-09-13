/**
 * Stable native logging API shared by applications, runtimes, and façade adapters.
 *
 * <p>The packages in the {@code logyard-api} artifact are Logyard's supported Java
 * contract. Application code normally enters through {@link com.logyard4j.logyard.api.Logyard}
 * or a {@link com.logyard4j.logyard.api.LogyardRuntime}; integrations implement contracts
 * from {@link com.logyard4j.logyard.api.spi} and exchange detached values from
 * {@link com.logyard4j.logyard.api.event}.</p>
 */
package com.logyard4j.logyard.api;
