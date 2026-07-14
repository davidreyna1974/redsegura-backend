-- Claves de idempotencia para deduplicar reintentos de creación (RN9/ADR-09).
-- La clave se acota por usuario (created_by): dos clientes distintos pueden usar la misma
-- cadena sin colisionar. request_hash es el SHA-256 del cuerpo canónico: un reintento con la
-- misma clave pero cuerpo distinto se rechaza (409), en vez de reproducir el original.
CREATE TABLE idempotency_keys (
    id            UUID         PRIMARY KEY,
    id_key        VARCHAR(255) NOT NULL,
    created_by    VARCHAR(255) NOT NULL,
    request_hash  VARCHAR(64)  NOT NULL,
    device_id     UUID         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_key_user UNIQUE (id_key, created_by)
);
