-- When the payment reached its current status. Reconciliation finds payments PROCESSING for too long by it.
-- Rows that exist before this migration get the migration's time: they look freshly changed, at worst one threshold late.
ALTER TABLE payment ADD COLUMN status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE payment ALTER COLUMN status_changed_at DROP DEFAULT;

CREATE INDEX payment_status_changed_at ON payment (status, status_changed_at);
