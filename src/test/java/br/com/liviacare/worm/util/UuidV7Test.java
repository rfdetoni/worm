package br.com.liviacare.worm.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UuidV7Test {

    @Test
    void generate_returnsNonNull() {
        assertNotNull(UuidV7.next());
    }

    @Test
    void generate_returnsVersion7() {
        UUID uuid = UuidV7.next();
        assertEquals(7, uuid.version());
    }

    @Test
    void generate_producesUniqueValues() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(UuidV7.next()), "Duplicate UUID generated at iteration " + i);
        }
    }

    @Test
    void generate_isMonotonicallyIncreasing() {
        UUID a = UuidV7.next();
        UUID b = UuidV7.next();
        assertTrue(a.getMostSignificantBits() <= b.getMostSignificantBits(),
                "UUIDs should be monotonically non-decreasing");
    }

    @Test
    void generate_hasVariantBitsSet() {
        UUID uuid = UuidV7.next();
        int variant = (int) ((uuid.getLeastSignificantBits() >>> 62) & 0x3);
        assertEquals(2, variant, "RFC-4122 variant must be 0b10");
    }
}

