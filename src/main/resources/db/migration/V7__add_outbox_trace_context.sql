-- Distributed tracing (docs/episodes/bonus-05). The W3C traceparent of the status change that wrote the row, so the
-- relay's send joins that trace instead of starting its own. Technical, not part of the event: null for rows written
-- before this migration or outside any trace. 55 characters: 00-<32 hex trace id>-<16 hex span id>-<2 hex flags>.
ALTER TABLE payment_outbox ADD COLUMN trace_context VARCHAR(55);
