package com.zsumz.logyard.output.json.file;

/** One active JSON Lines data file whose record-boundary integrity is owned by its caller. */
interface ActiveDataFile extends AutoCloseable {
    long logicalBytes();

    void write(byte[] record, byte terminator);

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
