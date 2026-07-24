package com.zsumz.logyard.runtime.management;

/** Source of the effective logger threshold reported through management integrations. */
public enum LoggerLevelOrigin {
    /** An exact or inherited runtime override currently wins. */
    RUNTIME_OVERRIDE,

    /** The logger has an exact threshold in the active Logyard configuration. */
    BASE_CONFIGURATION,

    /** The logger inherits its threshold from a configured ancestor or root. */
    INHERITED
}
