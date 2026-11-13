package com.cep.shared.internal;

import com.cep.shared.internal.jobs.JobsProperties;
import com.cep.shared.internal.messaging.OutboxProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JobsProperties.class, OutboxProperties.class})
public class SharedConfiguration {

    /** Single time source for the whole platform; tests replace it to simulate the passage of time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
