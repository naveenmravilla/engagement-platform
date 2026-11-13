package com.cep.auth.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiKeysTest {
    @Test
    void keysAreUniqueWellFormedAndHashedDeterministically() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String key = ApiKeys.generate();
            assertThat(ApiKeys.looksValid(key)).isTrue();
            assertThat(seen.add(key)).isTrue();
        }
        String key = ApiKeys.generate();
        assertThat(ApiKeys.hash(key)).hasSize(64).isEqualTo(ApiKeys.hash(key)).doesNotContain(key);
        assertThat(ApiKeys.displayPrefix(key)).hasSize(12).startsWith("cep_");
    }

    @Test
    void rejectsMalformedKeys() {
        assertThat(ApiKeys.looksValid(null)).isFalse();
        assertThat(ApiKeys.looksValid("cep_short")).isFalse();
        assertThat(ApiKeys.looksValid("xyz_" + "a".repeat(43))).isFalse();
    }
}
