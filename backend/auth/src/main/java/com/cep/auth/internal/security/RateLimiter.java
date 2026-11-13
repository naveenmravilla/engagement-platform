package com.cep.auth.internal.security;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter in Redis (atomic INCR + PEXPIRE via Lua). Shared by all app instances.
 * Fails open when Redis is unavailable: rate limiting is protective, not a correctness boundary,
 * and an outage should not take integration APIs down with it. Failures are counted in metrics.
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final DefaultRedisScript<Long> INCR = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return c
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final MeterRegistry meters;

    public RateLimiter(StringRedisTemplate redis, Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.clock = clock;
        this.meters = meters;
    }

    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {}

    public Decision acquire(String bucket, int limit, Duration window) {
        long now = clock.millis();
        long windowMs = window.toMillis();
        long windowIndex = now / windowMs;
        long resetIn = windowMs - (now % windowMs);
        try {
            Long count = redis.execute(INCR, List.of("cep:rl:" + bucket + ":" + windowIndex), String.valueOf(windowMs * 2));
            long c = count == null ? 0 : count;
            boolean allowed = c <= limit;
            if (!allowed) {
                meters.counter("cep.ratelimit.rejected", "bucket", bucket.substring(0, bucket.indexOf(':') + 1)).increment();
            }
            return new Decision(allowed, limit, (int) Math.max(0, limit - c), Math.max(1, resetIn / 1000));
        } catch (RuntimeException e) {
            meters.counter("cep.ratelimit.errors").increment();
            log.warn("Rate limiter unavailable, failing open: {}", e.toString());
            return new Decision(true, limit, limit, 0);
        }
    }
}
