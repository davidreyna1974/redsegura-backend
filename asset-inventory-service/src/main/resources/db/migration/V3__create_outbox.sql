-- Transactional outbox (RN11/ADR-04/RNF-E4): el evento se escribe en la MISMA transacción que la
-- mutación del dispositivo; un relay lo publica luego a RabbitMQ. Garantiza que no haya evento sin
-- commit ni doble escritura BD↔broker. published_at NULL = pendiente de publicar.
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY,
    event_id       UUID         NOT NULL UNIQUE,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ
);

-- El relay busca los pendientes por orden de creación; índice parcial sobre los no publicados.
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
