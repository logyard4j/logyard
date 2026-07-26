package com.zsumz.logyard.output.json.flush;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Print stream that holds a diagnostic write until a test explicitly releases it. */
final class BlockingPrintStream extends PrintStream {
    private final BlockingOutputStream output;

    BlockingPrintStream() {
        this(new BlockingOutputStream());
    }

    private BlockingPrintStream(BlockingOutputStream output) {
        super(output, true, StandardCharsets.UTF_8);
        this.output = output;
    }

    boolean awaitBlocked() throws InterruptedException {
        return output.awaitBlocked();
    }

    void release() {
        output.release();
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public void write(int value) {
            block();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            block();
        }

        private void block() {
            blocked.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    released.await();
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean awaitBlocked() throws InterruptedException {
            return blocked.await(2L, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }
    }
}
