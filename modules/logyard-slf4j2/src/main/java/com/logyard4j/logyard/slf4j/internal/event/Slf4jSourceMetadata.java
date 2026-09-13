package com.logyard4j.logyard.slf4j.internal.event;

import com.logyard4j.logyard.api.ingress.IngressMetadata;

final class Slf4jSourceMetadata {
    private Slf4jSourceMetadata() {
    }

    static IngressMetadata normalize(Long timestampMillis, String threadName) {
        if (timestampMillis == null || (timestampMillis == 0L && threadName == null)) {
            return IngressMetadata.current();
        }
        return IngressMetadata.source(timestampMillis, threadName);
    }
}
