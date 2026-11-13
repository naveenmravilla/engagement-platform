package com.cep.shared.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {
    @Test
    void generatesVersion7VariantRfcIds() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void idsAreTimeOrdered() {
        UUID earlier = Ids.newId(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        UUID later = Ids.newId(Clock.fixed(Instant.parse("2026-01-01T00:00:00.001Z"), ZoneOffset.UTC));
        assertThat(earlier.toString()).isLessThan(later.toString());
        assertThat(earlier.getMostSignificantBits() >>> 16).isEqualTo(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
    }
}
