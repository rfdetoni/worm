package br.com.liviacare.worm.config.metrics;

public interface LatencyRecorder {
    void record(String operation, long latencyNanos);
    void report();
}
