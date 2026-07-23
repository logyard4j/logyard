/**
 * Stable native logging API shared by applications, runtimes, and façade adapters.
 *
 * <p>The packages in the {@code logyard-api} artifact are Logyard's supported Java
 * contract. Application code normally enters through {@link com.zsumz.logyard.api.Logyard}
 * or a {@link com.zsumz.logyard.api.LogyardRuntime}; integrations implement contracts
 * from {@link com.zsumz.logyard.api.spi} and exchange detached values from
 * {@link com.zsumz.logyard.api.event}.</p>
 */
package com.zsumz.logyard.api;
