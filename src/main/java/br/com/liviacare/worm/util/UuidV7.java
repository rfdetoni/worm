package br.com.liviacare.worm.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

    private final AtomicReference<State> lastState;

    private UuidV7() {
        this(Instant.EPOCH, Long.MIN_VALUE);
    }

    private UuidV7(Instant initialTimestamp, long initialSequence) {
        this.lastState = new AtomicReference<>(new State(initialTimestamp, initialSequence));
    }

    public static UUID next() {
        return INSTANCE.generate();
    }

    private UUID generate() {
        State state = lastState.updateAndGet(State::getNextState);
        long ms = state.millis();
        long nanos = state.nanos & NANOS_MASK;
        // MSB: (ms << 16) | (version(7) << 12) | nanos
        long msb = ((ms << 16) & 0xFFFFFFFFFFFF0000L) | (0x7L << 12) | nanos;
        // LSB: variant bits (10xxxx) + sequence/random
        long lsb = Long.MIN_VALUE | state.lastSequence;
        return new UUID(msb, lsb);
    }

    // Holder for SecureRandom instance
    private static final class Holder {
        private static final SecureRandom numberGenerator = new SecureRandom();
    }

    // Immutable state record
    private static final class State {
        final Instant lastTimestamp;
        final long lastSequence;
        final long nanos; // 12-bit derived from timestamp nanos

        State(Instant lastTimestamp, long lastSequence) {
            this.lastTimestamp = lastTimestamp;
            this.lastSequence = lastSequence;
            this.nanos = nanosFromInstant(lastTimestamp);
        }

        long millis() {
            return this.lastTimestamp.toEpochMilli();
        }

        private static long nanosFromInstant(Instant timestamp) {
            // replicate the decompiled scaling: ((nano % 1_000_000) * 0.004096)
            long nanosPart = timestamp.getNano() % 1_000_000L; // micro-second portion
            return (long) ((double) nanosPart * 0.004096);
        }

        State getNextState() {
            Instant now = Instant.now();
            if (lastTimestampEarlierThan(now)) {
                return new State(now, randomSequence());
            } else {
                // add a random increment up to 2^32-1
                long inc = Holder.numberGenerator.nextLong(Integer.toUnsignedLong(0xFFFFFFFF));
                long nextSequence = this.lastSequence + inc;
                if (nextSequence > MAX_RANDOM_SEQUENCE) {
                    // push time forward slightly and pick a new random sequence
                    return new State(this.lastTimestamp.plusNanos(250L), randomSequence());
                } else {
                    return new State(this.lastTimestamp, nextSequence);
                }
            }
        }

        private boolean lastTimestampEarlierThan(Instant now) {
            long thisMs = this.lastTimestamp.toEpochMilli();
            long nowMs = now.toEpochMilli();
            if (thisMs < nowMs) return true;
            if (thisMs > nowMs) return false;
            // same ms -> compare scaled nanos
            long nowNanosScaled = nanosFromInstant(now);
            return this.nanos < nowNanosScaled;
        }

        private static long randomSequence() {
            return Holder.numberGenerator.nextLong(MAX_RANDOM_SEQUENCE);
        }
    }
}

