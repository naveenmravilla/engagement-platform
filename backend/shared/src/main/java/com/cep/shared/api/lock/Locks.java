package com.cep.shared.api.lock;

/** Cluster-wide mutual exclusion via Postgres advisory locks, scoped to the current transaction. */
public interface Locks {
    /** Blocks until the lock for {@code key} is held; released automatically at commit/rollback. */
    void lockForTransaction(String key);

    /** @return true if acquired; never blocks. Released automatically at commit/rollback. */
    boolean tryLockForTransaction(String key);
}
