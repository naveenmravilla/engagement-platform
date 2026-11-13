package com.cep.app.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Test clock: time only moves when a test says so. Lets us "wait 30 minutes" in milliseconds. */
public final class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    public void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }

    public void set(Instant instant) { now.set(instant); }

    @Override
    public Instant instant() { return now.get(); }

    @Override
    public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override
    public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
}
