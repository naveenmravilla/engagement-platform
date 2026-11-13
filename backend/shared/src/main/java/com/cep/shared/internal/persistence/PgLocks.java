package com.cep.shared.internal.persistence;

import com.cep.shared.api.lock.Locks;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PgLocks implements Locks {
    private final JdbcClient jdbc;

    public PgLocks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForTransaction(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key).query().singleRow();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLockForTransaction(String key) {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key)
                .query(Boolean.class).single();
    }
}
