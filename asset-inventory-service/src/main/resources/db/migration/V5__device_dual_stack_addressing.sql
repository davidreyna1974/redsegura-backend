-- RF-05a: direccionamiento de gestión dual-stack (ADR-13). Reemplaza el único mgmt_ip (IPv4) por
-- una dirección IPv4 y/o una IPv6, cada una con prefijo (CIDR) y gateway. Sin datos en producción
-- todavía, así que se sustituye la columna directamente (no hay migración de datos).
ALTER TABLE devices DROP CONSTRAINT uq_devices_mgmt_ip;
ALTER TABLE devices DROP COLUMN mgmt_ip;

ALTER TABLE devices
    ADD COLUMN mgmt_ipv4_address VARCHAR(15),
    ADD COLUMN mgmt_ipv4_prefix  INTEGER,
    ADD COLUMN mgmt_ipv4_gateway VARCHAR(15),
    ADD COLUMN mgmt_ipv6_address VARCHAR(45),
    ADD COLUMN mgmt_ipv6_prefix  INTEGER,
    ADD COLUMN mgmt_ipv6_gateway VARCHAR(45);

-- Unicidad por dirección de gestión (los NULL no colisionan en Postgres).
ALTER TABLE devices ADD CONSTRAINT uq_devices_mgmt_ipv4 UNIQUE (mgmt_ipv4_address);
ALTER TABLE devices ADD CONSTRAINT uq_devices_mgmt_ipv6 UNIQUE (mgmt_ipv6_address);

-- Al menos una dirección de gestión es obligatoria (RF-05a), reforzado en BD.
ALTER TABLE devices
    ADD CONSTRAINT ck_devices_has_mgmt_address
    CHECK (mgmt_ipv4_address IS NOT NULL OR mgmt_ipv6_address IS NOT NULL);
