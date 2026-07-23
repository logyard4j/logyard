/**
 * Internal console formatting and delivery for the batteries-included runtime.
 */
module com.zsumz.logyard.output.console {
    requires com.zsumz.logyard.api;

    exports com.zsumz.logyard.output.console to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.output.console.rendering to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.output.console.style to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.output.console.terminal to com.zsumz.logyard.runtime;
}
