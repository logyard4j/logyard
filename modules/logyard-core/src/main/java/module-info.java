/**
 * Internal routing, processing, and bounded delivery mechanics for Logyard.
 */
module com.zsumz.logyard.core {
    requires com.zsumz.logyard.api;

    exports com.zsumz.logyard.core.delivery to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.core.delivery.async to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.core.diagnostics to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.core.processing to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.core.routing to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.core.runtime to com.zsumz.logyard.runtime;
}
