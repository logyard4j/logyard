/**
 * SLF4J 2 provider backed by Logyard.
 */
module com.zsumz.logyard.slf4j2 {
    requires com.zsumz.logyard.runtime;
    requires org.slf4j;

    provides org.slf4j.spi.SLF4JServiceProvider with com.zsumz.logyard.slf4j.LogyardServiceProvider;
}
