"""Configuración por entorno (12-factor, RNF-22). Los valores se externalizan vía variables de
entorno; en tests se sobrescriben. Nunca se hardcodean secretos (RNF-06)."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Ajustes del servicio. Prefijo de entorno ``CBS_`` (p. ej. ``CBS_DB_URL``)."""

    model_config = SettingsConfigDict(env_prefix="CBS_", env_file=None, extra="ignore")

    # Persistencia y mensajería
    db_url: str = "postgresql+psycopg://cbs:changeme@localhost:5432/config_backup"
    rabbitmq_url: str = "amqp://guest:guest@localhost:5672/"

    # Seguridad (JWT de Keycloak) — validación issuer/audience (RNF-29)
    keycloak_jwks_uri: str = "http://localhost:8080/realms/redsegura/protocol/openid-connect/certs"
    keycloak_issuer: str = "http://localhost:8080/realms/redsegura"
    keycloak_audience: str = "redsegura-backend"

    # Repositorio Git interno de configuraciones (RF-07)
    git_repo_path: str = "/var/lib/config-backup/repo"

    # Credenciales SSH de los dispositivos (RNF-06: externalizadas, nunca en código/BD/logs).
    ssh_username: str = "admin"
    ssh_password: str = "changeme"
    ssh_device_type: str = "cisco_ios"

    # RNF-07: alcance de conexión SSH — solo CIDRs autorizados (dev/test = red simulada).
    # Lista separada por comas (p. ej. "10.0.0.0/8,192.168.0.0/16").
    allowed_scan_cidrs: str = "10.0.0.0/8,192.168.0.0/16"


def get_settings() -> Settings:
    """Punto único de acceso a la configuración (inyectable/overridable en tests)."""
    return Settings()
