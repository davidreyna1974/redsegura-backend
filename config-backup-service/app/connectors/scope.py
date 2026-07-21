"""Control técnico de alcance de conexión (RNF-07): solo se permite conectar a IPs dentro de los
CIDRs autorizados (en dev/test, la red simulada). Control **obligatorio**, no solo política."""

from __future__ import annotations

from ipaddress import ip_address, ip_network


class OutOfScopeError(Exception):
    """El objetivo está fuera de los CIDRs autorizados (RNF-07)."""


def parse_cidrs(raw: str) -> list[str]:
    return [c.strip() for c in raw.split(",") if c.strip()]


def is_in_scope(host: str, allowed_cidrs: str) -> bool:
    addr = ip_address(host)
    return any(addr in ip_network(cidr) for cidr in parse_cidrs(allowed_cidrs))


def assert_in_scope(host: str, allowed_cidrs: str) -> None:
    if not is_in_scope(host, allowed_cidrs):
        raise OutOfScopeError(f"{host} está fuera del alcance de conexión autorizado (RNF-07)")
