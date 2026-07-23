package com.zsumz.logyard.runtime.bootstrap;

/** Process participant holding an ownership lease on the shared Logyard runtime. */
public enum RuntimeOwner {
    /** Application-controlled native bootstrap. */
    APPLICATION,

    /** Framework lifecycle integration such as Spring Boot or Quarkus. */
    FRAMEWORK,

    /** Logging façade adapter such as SLF4J, JUL, or {@link System.Logger}. */
    ADAPTER
}
