package com.logyard4j.api.event;

/** One bounded text field plus an exact indication that capture shortened it. */
record CapturedText(String value, boolean truncated) {
}
