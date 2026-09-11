package com.logyard4j.output.json.file;

import java.io.IOException;

/**
 * Makes already-written bytes durable before a flush is considered complete.
 *
 * <p>Every flush path of a file output funnels through {@link BufferedFileWriter#flush()} or
 * {@link BufferedFileWriter#close()}, so a barrier installed on the active data file covers
 * record-driven flushes, timed flushes, the close that precedes a rotation, and the close taken at
 * shutdown. A barrier that fails follows the same fail-closed rules as a failed flush: the uncertain
 * buffered bytes are discarded and the writer becomes terminal.</p>
 */
@FunctionalInterface
interface DurabilityBarrier {
    /** Barrier for outputs that leave durability to the operating system. */
    DurabilityBarrier OPERATING_SYSTEM = () -> {
    };

    void sync() throws IOException;
}
