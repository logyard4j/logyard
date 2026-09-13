package com.logyard4j.output.json.file;

/** One active JSON Lines data file whose record-boundary integrity is owned by its caller. */
interface ActiveDataFile extends AutoCloseable {
    long logicalBytes();

    default void write(byte[] record, byte terminator) {
        write(record, record.length, terminator);
    }

    /** Consumes the record prefix before returning, without retaining or changing its storage. */
    void write(byte[] record, int length, byte terminator);

    void flush();

    /**
     * Closes the data file.
     *
     * <p>After {@link #write(byte[], byte)} or {@link #flush()} fails, closing must discard any
     * uncertain buffered bytes rather than retrying them.</p>
     */
    @Override
    void close();
}
