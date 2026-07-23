/**
 * JDK System.Logger provider backed by Logyard.
 */
module com.zsumz.logyard.system.logger {
    requires com.zsumz.logyard.runtime;

    provides java.lang.System.LoggerFinder with com.zsumz.logyard.systemlogger.LogyardLoggerFinder;
}
