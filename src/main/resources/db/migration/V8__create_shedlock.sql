-- Scheduler locks (docs/episodes/bonus-07). One row per scheduled job that only one instance may run at a time:
-- reconciliation and outbox cleanup. ShedLock's own layout; lock_until is when a crashed holder's lock expires.
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
