package com.github.rfdetoni.worm.config.metrics;

public class NoOpLatencyRecorder implements LatencyRecorder {
    @Override
    public void record(String operation, long latencyNanos) {
        // No-op
    }

    @Override
    public void report() {
        // No-op
    }
}
