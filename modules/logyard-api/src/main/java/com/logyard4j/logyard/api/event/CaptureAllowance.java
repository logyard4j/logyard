package com.logyard4j.logyard.api.event;

/** Remaining payload capacity that may be reused when an event replaces its attributes. */
record CaptureAllowance(int nodes, int entries, int characters) {
    private static final CaptureAllowance FULL = new CaptureAllowance(
            CaptureLimits.MAX_EVENT_NODES, CaptureLimits.MAX_EVENT_ENTRIES, CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);

    CaptureAllowance {
        nodes = Math.max(0, nodes);
        entries = Math.max(0, entries);
        characters = Math.max(0, characters);
    }

    static CaptureAllowance remaining(int nodes, int entries, int characters) {
        return nodes == FULL.nodes && entries == FULL.entries && characters == FULL.characters
                ? FULL : new CaptureAllowance(nodes, entries, characters);
    }
}
