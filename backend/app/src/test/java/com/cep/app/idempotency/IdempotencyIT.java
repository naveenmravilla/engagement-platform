package com.cep.app.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cep.app.support.IntegrationTest;
import com.cep.shared.api.error.ApiException;
import com.cep.shared.api.idempotency.IdempotencyService;
import com.cep.shared.api.idempotency.IdempotencyService.Result;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyIT extends IntegrationTest {
    @Autowired IdempotencyService idempotency;

    record Response(String executionId) {}

    @Test
    void replayReturnsOriginalResponseWithoutRerunning() {
        UUID tenant = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        Result<Response> first = idempotency.execute(tenant, "execute", "REQ1", "h1", Response.class,
                () -> new Response("EXEC-" + runs.incrementAndGet()));
        Result<Response> again = idempotency.execute(tenant, "execute", "REQ1", "h1", Response.class,
                () -> new Response("EXEC-" + runs.incrementAndGet()));

        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(again.value()).isEqualTo(first.value());
        assertThat(runs).hasValue(1);
    }

    @Test
    void sameKeyDifferentPayloadIsConflict() {
        UUID tenant = UUID.randomUUID();
        idempotency.execute(tenant, "execute", "REQ2", "h1", Response.class, () -> new Response("A"));
        assertThatThrownBy(() -> idempotency.execute(tenant, "execute", "REQ2", "h2", Response.class, () -> new Response("B")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void keysAreTenantScoped() {
        Result<Response> a = idempotency.execute(UUID.randomUUID(), "s", "SAME", "h", Response.class, () -> new Response("A"));
        Result<Response> b = idempotency.execute(UUID.randomUUID(), "s", "SAME", "h", Response.class, () -> new Response("B"));
        assertThat(a.replayed()).isFalse();
        assertThat(b.replayed()).isFalse();
    }

    @Test
    void failedActionReleasesKey() {
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> idempotency.execute(tenant, "s", "K", "h", Response.class, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(idempotency.execute(tenant, "s", "K", "h", Response.class, () -> new Response("ok")).replayed()).isFalse();
    }

    @Test
    void concurrentDuplicatesRunActionOnce() throws Exception {
        UUID tenant = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Result<Response>>> calls = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return idempotency.execute(tenant, "execute", "RACE", "h", Response.class, () -> {
                        runs.incrementAndGet();
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                        return new Response("ONLY");
                    });
                })).toList();
        go.countDown();
        List<Result<Response>> results = calls.stream().map(CompletableFuture::join).toList();

        assertThat(runs).hasValue(1);
        assertThat(results).extracting(Result::value).containsOnly(new Response("ONLY"));
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
    }
}
