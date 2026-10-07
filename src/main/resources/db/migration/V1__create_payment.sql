CREATE TABLE payment (
    id                     UUID           PRIMARY KEY,
    source_account_id      VARCHAR(64)    NOT NULL,
    destination_account_id VARCHAR(64)    NOT NULL,
    amount                 NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency               VARCHAR(3)     NOT NULL,
    reference              VARCHAR(140)   NOT NULL,
    status                 VARCHAR(20)    NOT NULL
);
