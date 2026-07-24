package com.zsumz.logyard.output.json.encoding;

/** Internal control signal used to replace an oversized record with bounded valid JSON. */
final class JsonLimitExceeded extends RuntimeException {
    private static final long serialVersionUID = 1L;
    static final JsonLimitExceeded INSTANCE = new JsonLimitExceeded();

    private JsonLimitExceeded() {
        super(null, null, false, false);
    }
}
