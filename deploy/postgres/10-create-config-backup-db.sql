-- Crea la base de datos propia de config-backup-service (database-per-service). Se ejecuta una sola
-- vez, al inicializar el volumen de Postgres (docker-entrypoint-initdb.d). En dev ambas bases viven
-- en la misma instancia por simplicidad; en producción cada servicio tiene su instancia/credenciales.
CREATE DATABASE config_backup OWNER redsegura;
