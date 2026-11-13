-- Test-only table used to prove job handler writes commit/rollback atomically with job state.
CREATE TABLE test_side_effects (
    effect_key  varchar(100) NOT NULL,
    attempt     int          NOT NULL
);
