package com.logyard4j.runtime.assembly.output;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Objects;

/** Converts suppressed process-stream failures at each encoded byte batch and explicit flush. */
final class CheckedPrintStream extends OutputStream {
    private final PrintStream stream;

    CheckedPrintStream(PrintStream stream) {
        this.stream = Objects.requireNonNull(stream, "stream");
    }

    @Override
    public void write(int value) throws IOException {
        stream.write(value);
        check();
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        stream.write(bytes, offset, length);
        check();
    }

    @Override
    public void flush() throws IOException {
        check();
    }

    @Override
    public void close() throws IOException {
        flush(); // The process owns stdout/stderr; never close either stream.
    }

    private void check() throws IOException {
        // checkError itself flushes. The enclosing OutputStreamWriter retains small records
        // until its byte buffer fills or an explicit or timed flush drains that buffer.
        if (stream.checkError()) {
            throw new IOException("Logyard process stream reported an I/O error");
        }
    }
}
