"""Seguridad: validación de JWT en profundidad (firma vía JWKS + issuer + audience + expiración,
RNF-29/ADR-14) y RBAC por endpoint (RNF-04). No se confía en que el Gateway ya filtró."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Annotated, Any

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import Settings, get_settings


@dataclass(frozen=True)
class Principal:
    subject: str
    roles: frozenset[str]


_bearer = HTTPBearer(auto_error=False)


def _decode(token: str, settings: Settings) -> dict[str, Any]:
    signing_key = jwt.PyJWKClient(settings.keycloak_jwks_uri).get_signing_key_from_jwt(token)
    claims: dict[str, Any] = jwt.decode(
        token,
        signing_key.key,
        algorithms=["RS256"],
        issuer=settings.keycloak_issuer,
        audience=settings.keycloak_audience,
    )
    return claims


def get_principal(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> Principal:
    if credentials is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Falta el token de autenticación")
    try:
        claims = _decode(credentials.credentials, settings)
    except jwt.InvalidTokenError as error:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Token inválido") from error
    realm_access = claims.get("realm_access") or {}
    roles = realm_access.get("roles") or []
    return Principal(subject=str(claims.get("sub", "")), roles=frozenset(roles))


def require_roles(*allowed: str) -> Callable[[Principal], Principal]:
    """Dependencia RBAC: exige que el principal tenga al menos uno de ``allowed``."""

    def checker(principal: Annotated[Principal, Depends(get_principal)]) -> Principal:
        if principal.roles.isdisjoint(allowed):
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Rol sin permiso para esta operación")
        return principal

    return checker
