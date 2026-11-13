package com.cep.shared.api.util;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** UUIDv7 generator (RFC 9562): time-ordered, so B-tree inserts stay append-mostly. */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static UUID newId() {
        return newId(Clock.systemUTC());
    }

    public static UUID newId(Clock clock) {
        long millis = clock.millis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
