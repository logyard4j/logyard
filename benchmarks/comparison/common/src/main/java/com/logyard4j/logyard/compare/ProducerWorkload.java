package com.logyard4j.logyard.compare;

import org.slf4j.Logger;

/** Caller preparation and one identified publication, shared by bounded producer schedules. */
public interface ProducerWorkload {
    void prepareThread();

    void log(Logger logger, int index);
}
