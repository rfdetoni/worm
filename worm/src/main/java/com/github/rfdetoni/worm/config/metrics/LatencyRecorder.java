package com.github.rfdetoni.worm.config.metrics;

public interface LatencyRecorder {
    void record(String operation, long latencyNanos);
    void report();
}
