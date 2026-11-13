package com.cep.shared.internal.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {
    @Test
    void exponentialWithCap() {
        Backoff b = new Backoff(Duration.ofSeconds(30), 4, Duration.ofHours(1));
        assertThat(b.delayAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(b.delayAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(b.delayAfter(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(b.delayAfter(4)).isEqualTo(Duration.ofMinutes(32));
        assertThat(b.delayAfter(5)).isEqualTo(Duration.ofHours(1));
        assertThat(b.delayAfter(50)).isEqualTo(Duration.ofHours(1));
    }
}
