package com.github.rfdetoni.worm.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UUIDv7-like generator implemented in a way compatible with Hibernate's
 * UuidVersion7Strategy decompiled logic: time in MSB (ms &lt;&lt; 16), version 7
 * placed in the MSB low bits, and a 12-bit nanos/seq value. LSB contains a
 * large sequence/random value with RFC-4122 variant bits set.
 *
 * This implementation prefers compatibility with the referenced strategy
 * (similar layout and sequence behavior) rather than the lightweight
 * synchronized sequence generator used previously.
 */
public final class UuidV7 {

    private static final long MAX_RANDOM_SEQUENCE = 0x3FFFFFFFFFFFFFFFL; // 4611686018427387903L
    private static final long NANOS_MASK = 0xFFFL; // 12 bits
    public static final UuidV7 INSTANCE = new UuidV7();

    private final AtomicLong globalMillis;
    private final ThreadLocal<State> localState;

    private UuidV7() {
        this(Instant.EPOCH);
    }

    private UuidV7(Instant initialTimestamp) {
        this.globalMillis = new AtomicLong(initialTimestamp.toEpochMilli());
        this.localState = ThreadLocal.withInitial(() -> new State(initialTimestamp.toEpochMilli(), nanosFromInstant(initialTimestamp), randomSequence()));
    }

    public static UUID next() {
        return INSTANCE.generate();
    }

    private UUID generate() {
        State next = nextState();
        long ms = next.millis;
        long nanos = next.nanos & NANOS_MASK;
        // MSB: (ms << 16) | (version(7) << 12) | nanos
        long msb = ((ms << 16) & 0xFFFFFFFFFFFF0000L) | (0x7L << 12) | nanos;
        // LSB: variant bits (10xxxx) + sequence/random
        long lsb = Long.MIN_VALUE | next.lastSequence;
        return new UUID(msb, lsb);
    }

    private State nextState() {
        State current = localState.get();
        Instant now = Instant.now();
        long nowMillis = now.toEpochMilli();
        long nowNanos = nanosFromInstant(now);

        // PERF: coordinate epoch millis with one shared atomic long while keeping per-thread state local.
        long coordinatedMillis = globalMillis.accumulateAndGet(nowMillis, Math::max);
        long nextMillis = Math.max(coordinatedMillis, current.millis);

        State next;
        if (nextMillis > current.millis) {
            next = new State(nextMillis, nowNanos, randomSequence());
        } else if (nowNanos > current.nanos) {
            next = new State(current.millis, nowNanos, randomSequence());
        } else {
            long inc = Holder.numberGenerator.nextLong(Integer.toUnsignedLong(0xFFFFFFFF));
            long seq = current.lastSequence + inc;
            if (seq > MAX_RANDOM_SEQUENCE) {
                long bumpedMillis = current.millis + 1;
                // PERF: keep threads monotonic without CAS retry loops by monotonic bump on overflow.
                globalMillis.accumulateAndGet(bumpedMillis, Math::max);
                next = new State(bumpedMillis, nowNanos, randomSequence());
            } else {
                next = new State(current.millis, current.nanos, seq);
            }
        }
        localState.set(next);
        return next;
    }

    // Holder for SecureRandom instance
    private static final class Holder {
        private static final SecureRandom numberGenerator = new SecureRandom();
    }

    // Immutable state holder
    private static final class State {
        final long millis;
        final long nanos;
        final long lastSequence;

        State(long millis, long nanos, long lastSequence) {
            this.millis = millis;
            this.nanos = nanos;
            this.lastSequence = lastSequence;
        }
    }

    private static long nanosFromInstant(Instant timestamp) {
        long nanosPart = timestamp.getNano() % 1_000_000L;
        return (long) ((double) nanosPart * 0.004096);
    }

    private static long randomSequence() {
        return Holder.numberGenerator.nextLong(MAX_RANDOM_SEQUENCE);
    }
}

