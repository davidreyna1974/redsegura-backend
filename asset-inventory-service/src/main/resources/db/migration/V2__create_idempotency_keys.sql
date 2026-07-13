-- Claves de idempotencia para deduplicar reintentos de creación (RN9/ADR-09).
-- Alcance MVP: global por clave. En un sistema real se acotaría por usuario/endpoint.
CREATE TABLE idempotency_keys (
    id_key      VARCHAR(255) PRIMARY KEY,
    device_id   UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
