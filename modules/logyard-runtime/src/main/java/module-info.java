/**
 * Batteries-included Logyard runtime and supported bootstrap API.
 */
module com.zsumz.logyard.runtime {
    requires transitive com.zsumz.logyard.api;
    requires com.zsumz.logyard.config.toml;
    requires com.zsumz.logyard.core;
    requires com.zsumz.logyard.output.console;
    requires com.zsumz.logyard.output.json;

    exports com.zsumz.logyard.runtime.bootstrap;
    exports com.zsumz.logyard.runtime.adapter to com.zsumz.logyard.jul, com.zsumz.logyard.slf4j2, com.zsumz.logyard.system.logger;
    exports com.zsumz.logyard.runtime.context to com.zsumz.logyard.slf4j2;
    exports com.zsumz.logyard.runtime.diagnostics to com.zsumz.logyard.jul, com.zsumz.logyard.system.logger;

    uses com.zsumz.logyard.api.spi.context.ContextProvider;
    uses com.zsumz.logyard.api.spi.encoding.EventEncoderProvider;
    uses com.zsumz.logyard.api.spi.formatting.TextFormatterProvider;
    uses com.zsumz.logyard.api.spi.output.OutputProvider;
    uses com.zsumz.logyard.api.spi.processing.EventProcessorProvider;
}
