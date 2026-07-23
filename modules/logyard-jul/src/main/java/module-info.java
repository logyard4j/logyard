/**
 * java.util.logging adapter backed by Logyard.
 */
module com.zsumz.logyard.jul {
    requires transitive com.zsumz.logyard.api;
    requires com.zsumz.logyard.runtime;
    requires java.logging;

    exports com.zsumz.logyard.jul;
}
