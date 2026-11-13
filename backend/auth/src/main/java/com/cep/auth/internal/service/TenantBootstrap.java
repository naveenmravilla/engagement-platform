package com.cep.auth.internal.service;

import com.cep.auth.api.Roles;
import com.cep.auth.internal.domain.Tenant;
import com.cep.auth.internal.domain.TenantRepository;
import com.cep.auth.internal.domain.UserRepository;
import com.cep.shared.api.util.Ids;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Idempotently ensures the bootstrap tenant and admin exist when CEP_BOOTSTRAP_ADMIN_* is configured. */
@Component
public class TenantBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TenantBootstrap.class);

    private final BootstrapProperties props;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final UserService userService;
    private final Clock clock;

    public TenantBootstrap(BootstrapProperties props, TenantRepository tenants, UserRepository users,
                           UserService userService, Clock clock) {
        this.props = props;
        this.tenants = tenants;
        this.users = users;
        this.userService = userService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (props.adminEmail() == null || props.adminEmail().isBlank()) {
            log.info("Bootstrap admin not configured; skipping tenant bootstrap");
            return;
        }
        Tenant tenant = tenants.findByTenantKey(props.tenantKey()).orElseGet(() -> {
            log.info("Bootstrapping tenant '{}'", props.tenantKey());
            return tenants.save(new Tenant(Ids.newId(clock), props.tenantKey(), props.tenantName(), clock.instant()));
        });
        String email = props.adminEmail().trim().toLowerCase(Locale.ROOT);
        if (!users.existsByTenantIdAndEmail(tenant.getId(), email)) {
            userService.createInternal(tenant.getId(), email, props.adminPassword(), "Administrator", Set.of(Roles.ADMIN));
            log.info("Bootstrapped admin user {} for tenant '{}'", email, props.tenantKey());
        }
    }
}
