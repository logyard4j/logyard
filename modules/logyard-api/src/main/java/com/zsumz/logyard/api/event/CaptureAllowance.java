package com.zsumz.logyard.api.event;

/** Remaining payload capacity that may be reused when an event replaces its attributes. */
record CaptureAllowance(int nodes, int entries, int characters) {
    CaptureAllowance {
        nodes = Math.max(0, nodes);
        entries = Math.max(0, entries);
        characters = Math.max(0, characters);
    }
}
