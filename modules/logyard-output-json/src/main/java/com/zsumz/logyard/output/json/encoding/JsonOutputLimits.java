package com.zsumz.logyard.output.json.encoding;

/** Independent defensive ceilings applied by the built-in JSON output. */
final class JsonOutputLimits {
    static final int MAX_RECORD_CHARACTERS = 262_144;

    private JsonOutputLimits() {
    }
}
