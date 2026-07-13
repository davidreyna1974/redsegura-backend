-- Inventario de dispositivos de red (fuente de verdad, RF-01..05).
-- Identidad estable por serial_number; hostname y mgmt_ip únicos pero mutables (RN1).
-- Columnas de auditoría estándar (ADR-07) y version para bloqueo optimista (RN8).
CREATE TABLE devices (
    id              UUID         PRIMARY KEY,
    serial_number   VARCHAR(100) NOT NULL,
    asset_tag       VARCHAR(100),
    hostname        VARCHAR(255) NOT NULL,
    mgmt_ip         VARCHAR(45)  NOT NULL,
    device_type     VARCHAR(20)  NOT NULL,
    vendor          VARCHAR(255),
    model           VARCHAR(255),
    loc_site        VARCHAR(255),
    loc_room        VARCHAR(255),
    loc_row         VARCHAR(255),
    loc_rack        VARCHAR(255),
    loc_rack_unit   INTEGER,
    criticality     VARCHAR(10)  NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(255),
    CONSTRAINT uq_devices_serial   UNIQUE (serial_number),
    CONSTRAINT uq_devices_hostname UNIQUE (hostname),
    CONSTRAINT uq_devices_mgmt_ip  UNIQUE (mgmt_ip)
);

CREATE INDEX idx_devices_status ON devices (status);
CREATE INDEX idx_devices_device_type ON devices (device_type);
CREATE INDEX idx_devices_criticality ON devices (criticality);
