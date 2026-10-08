-- No foreign key to payment: the key is claimed before the payment row is written.
CREATE TABLE idempotency_key (
    idempotency_key     VARCHAR(255) PRIMARY KEY,
    request_fingerprint VARCHAR(64)  NOT NULL,
    payment_id          UUID         NOT NULL
);
