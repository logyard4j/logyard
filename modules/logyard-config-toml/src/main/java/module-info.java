/**
 * Internal TOML configuration model and loading pipeline for Logyard.
 */
module com.zsumz.logyard.config.toml {
    requires com.zsumz.logyard.api;

    exports com.zsumz.logyard.config to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.config.delivery to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.config.encoding to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.config.extension to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.config.formatting to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.config.loading to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.config.logging to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.config.output to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.config.processing to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.config.runtime to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.config.theme to com.zsumz.logyard.runtime;
}
