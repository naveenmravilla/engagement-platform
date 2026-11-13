package com.cep.shared.internal.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("cep.jobs")
public record JobsProperties(
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("2") int workerThreads,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5m") Duration leaseDuration,
        @DefaultValue("30s") Duration backoffInitial,
        @DefaultValue("4") double backoffMultiplier,
        @DefaultValue("1h") Duration backoffMax) {
}
