package br.com.liviacare.worm.config.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class WormLatencyRecorder implements LatencyRecorder {

    private static final Logger log = LoggerFactory.getLogger(WormLatencyRecorder.class);
    private static final int BUFFER_SIZE = 4096;

    private final ConcurrentHashMap<String, OperationMetrics> metrics = new ConcurrentHashMap<>();

    @Override
    public void record(String operation, long latencyNanos) {
        metrics.computeIfAbsent(operation, k -> new OperationMetrics()).record(latencyNanos);
    }

    @Override
    public void report() {
        log.info("WORM Latency Report (nanoseconds):");
        metrics.forEach((operation, metrics) -> {
            long[] snapshot = metrics.snapshot();
            Arrays.sort(snapshot);
            log.info("Operation: {}", operation);
            log.info("  p50: {}", percentile(snapshot, 50));
            log.info("  p95: {}", percentile(snapshot, 95));
            log.info("  p99: {}", percentile(snapshot, 99));
        });
    }

    private long percentile(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length);
        return sorted[Math.max(0, index - 1)];
    }

    private static class OperationMetrics {
        private final long[] buffer = new long[BUFFER_SIZE];
        private final AtomicLong index = new AtomicLong(0);
        private final AtomicLong count = new AtomicLong(0);

        void record(long latency) {
            long currentIndex = index.getAndIncrement();
            buffer[(int) (currentIndex % BUFFER_SIZE)] = latency;
            count.getAndIncrement();
        }

        long[] snapshot() {
            int snapshotSize = (int) Math.min(count.get(), BUFFER_SIZE);
            long[] snapshot = new long[snapshotSize];
            System.arraycopy(buffer, 0, snapshot, 0, snapshotSize);
            return snapshot;
        }
    }
}
