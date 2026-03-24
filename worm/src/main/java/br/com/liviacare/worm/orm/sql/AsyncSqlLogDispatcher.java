package br.com.liviacare.worm.orm.sql;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking async dispatcher for SQL log work.
 */
final class AsyncSqlLogDispatcher {

    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor executor;

    AsyncSqlLogDispatcher(int queueSize) {
        int resolvedQueueSize = Math.max(256, queueSize);
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(resolvedQueueSize),
                runnable -> Thread.ofPlatform()
                        .name("worm-sql-log-dispatcher")
                        .daemon(true)
                        .unstarted(runnable),
                (ignoredRunnable, ignoredExecutor) -> dropped.incrementAndGet()
        );
        this.executor.prestartCoreThread();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.shutdown();
            try {
                executor.awaitTermination(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "worm-sql-log-shutdown"));
    }

    void submit(Runnable task) {
        if (task == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable ignored) {
                    // Logging must never affect application flow.
                }
            });
        } catch (Throwable ignored) {
            // If the executor is unavailable, skip logging work.
        }
    }

    long droppedCount() {
        return dropped.get();
    }
}

