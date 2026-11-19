package dev.forexsignals.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Batches items on a virtual thread and hands each batch to a writer (a DB insert), so the engine loop
 * never waits for the database. Flushes when the batch is full or every {@code flushInterval}.
 * A failed batch is logged and counted, then dropped: recording must never stall the live engine.
 */
public final class AsyncWriter<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncWriter.class);
    private static final Object POISON = new Object();

    private final String name;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Consumer<List<T>> writer;
    private final int batchSize;
    private final Duration flushInterval;
    private final AtomicLong written = new AtomicLong(), failed = new AtomicLong();
    private final Thread thread;

    public AsyncWriter(String name, int batchSize, Duration flushInterval, Consumer<List<T>> writer) {
        this.name = name;
        this.batchSize = batchSize;
        this.flushInterval = flushInterval;
        this.writer = writer;
        this.thread = Thread.ofVirtual().name("writer-" + name).start(this::loop);
    }

    public void add(T item) { queue.add(item); }

    public long written() { return written.get(); }

    public long failed() { return failed.get(); }

    @SuppressWarnings("unchecked")
    private void loop() {
        List<T> batch = new ArrayList<>(batchSize);
        long deadline = System.nanoTime() + flushInterval.toNanos();
        boolean stop = false;
        while (!stop) {
            try {
                Object o = queue.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (o == POISON) stop = true;
                else if (o != null) batch.add((T) o);
            } catch (InterruptedException e) {
                stop = true;
            }
            if (stop || batch.size() >= batchSize || System.nanoTime() >= deadline) {
                if (stop) queue.drainTo((List<Object>) (List<?>) batch); // anything queued before close
                batch.removeIf(x -> x == POISON);
                if (!batch.isEmpty()) {
                    try {
                        writer.accept(batch);
                        written.addAndGet(batch.size());
                    } catch (RuntimeException e) {
                        failed.addAndGet(batch.size());
                        log.error("writer {}: batch of {} failed: {}", name, batch.size(), e.toString());
                    }
                    batch = new ArrayList<>(batchSize);
                }
                deadline = System.nanoTime() + flushInterval.toNanos();
            }
        }
    }

    /** Writes everything queued so far, then stops. */
    @Override
    public void close() {
        queue.add(POISON);
        try {
            thread.join(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
