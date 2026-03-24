package br.com.liviacare.worm.config.warmup;

public class NoOpWarmupStrategy implements WarmupStrategy {
    @Override
    public void warmup() {
        // No-op
    }
}
