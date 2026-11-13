package com.cep.auth.internal.service;

import com.cep.shared.api.util.Hashing;
import java.security.SecureRandom;
import java.util.Base64;

/** API keys: {@code cep_} + 256 bits of randomness. Stored only as SHA-256 (keys are high-entropy, so no KDF needed). */
public final class ApiKeys {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final String PREFIX = "cep_";

    private ApiKeys() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawKey) {
        return Hashing.sha256Hex(rawKey);
    }

    /** Non-secret display prefix, e.g. {@code cep_Ab3dE6gH}. */
    public static String displayPrefix(String rawKey) {
        return rawKey.substring(0, Math.min(12, rawKey.length()));
    }

    public static boolean looksValid(String rawKey) {
        return rawKey != null && rawKey.startsWith(PREFIX) && rawKey.length() == PREFIX.length() + 43;
    }
}
