-- Importación masiva asíncrona (RF-04): un job por lote y su resultado por dispositivo.
-- El POST /devices/bulk crea el job (QUEUED) y un worker lo procesa; GET jobs/{id} devuelve el estado.
CREATE TABLE import_jobs (
    id              UUID         PRIMARY KEY,
    status          VARCHAR(20)  NOT NULL,
    total           INTEGER      NOT NULL,
    succeeded       INTEGER      NOT NULL DEFAULT 0,
    failed          INTEGER      NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(255),
    created_by      VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    -- Idempotencia a nivel job, acotada por usuario (NULLs no colisionan en Postgres).
    CONSTRAINT uq_import_job_key_user UNIQUE (idempotency_key, created_by)
);

CREATE TABLE import_job_results (
    id            UUID         PRIMARY KEY,
    job_id        UUID         NOT NULL REFERENCES import_jobs (id) ON DELETE CASCADE,
    serial_number VARCHAR(100) NOT NULL,
    outcome       VARCHAR(20)  NOT NULL,
    detail        VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_import_job_results_job ON import_job_results (job_id);
