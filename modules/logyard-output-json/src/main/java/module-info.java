/**
 * Internal JSON encoding and delivery for the batteries-included runtime.
 */
module com.zsumz.logyard.output.json {
    requires com.zsumz.logyard.api;

    exports com.zsumz.logyard.output.json.encoding to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.output.json.file to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.output.json.file.rotation to com.zsumz.logyard.runtime;
    exports com.zsumz.logyard.output.json.stream to com.zsumz.logyard.runtime;
}
