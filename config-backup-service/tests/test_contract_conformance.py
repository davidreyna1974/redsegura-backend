"""Conformidad contrato↔implementación (prevención de HALLAZGO-QA-CBS-01/02, lección L-QA-08).

El ``openapi.yaml`` es la fuente de verdad; este test **falla en CI** si la implementación (el
esquema que genera FastAPI a partir del código) **no honra** algo declarado en el contrato: una
operación (path+método), un parámetro de query, o una cabecera. Es el gate ejecutable que caza
"declarado pero no implementado" — la clase de bug que ni los tests de comportamiento ni la
revalidación detectan, porque una funcionalidad ausente no tiene test que falle.

Alcance: **el contrato debe ser un subconjunto de la implementación** (la impl puede exponer extras
como ``/metrics`` o ``/docs``). Se comparan operaciones + parámetros ``query``/``header``. Los
nombres de parámetros de *path* se omiten a propósito (el contrato usa camelCase y el código
snake_case; su presencia queda implícita en el emparejamiento de la ruta)."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import yaml
from app.main import create_app

CONTRACT_PATH = Path(__file__).resolve().parent.parent / "openapi.yaml"
BASE = "/api/v1"  # `servers[].url` del contrato (prefijo de los routers)
_METHODS = {"get", "post", "put", "patch", "delete"}
ParamSet = set[tuple[str, str]]  # (nombre, ubicación) para query/header


def _normalize(path: str) -> str:
    """Colapsa los nombres de parámetros de ruta a ``{}`` para emparejar contrato (camelCase) con
    implementación (snake_case): ``/devices/{deviceId}/x`` ≡ ``/devices/{device_id}/x``."""
    return re.sub(r"\{[^}]+\}", "{}", path)


def _params(raw: list[dict[str, Any]], components: dict[str, Any]) -> ParamSet:
    out: ParamSet = set()
    for p in raw:
        if "$ref" in p:
            name = p["$ref"].split("/")[-1]
            p = components.get(name, {})
        if p.get("in") in ("query", "header"):
            out.add((p["name"], p["in"]))
    return out


def _contract_ops() -> dict[tuple[str, str], ParamSet]:
    spec = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    components = spec.get("components", {}).get("parameters", {})
    ops: dict[tuple[str, str], ParamSet] = {}
    for path, item in spec["paths"].items():
        shared = item.get("parameters", [])
        for method, op in item.items():
            if method not in _METHODS:
                continue
            key = (_normalize(BASE + path), method)
            ops[key] = _params(shared + op.get("parameters", []), components)
    return ops


def _app_ops() -> dict[tuple[str, str], ParamSet]:
    schema = create_app().openapi()
    ops: dict[tuple[str, str], ParamSet] = {}
    for path, item in schema["paths"].items():
        for method, op in item.items():
            if method not in _METHODS:
                continue
            params = {
                (p["name"], p["in"])
                for p in op.get("parameters", [])
                if p.get("in") in ("query", "header")
            }
            ops[(_normalize(path), method)] = params
    return ops


def test_every_contract_operation_is_implemented() -> None:
    contract, impl = _contract_ops(), _app_ops()
    missing = sorted(f"{m.upper()} {p}" for (p, m) in contract if (p, m) not in impl)
    assert not missing, f"Operaciones declaradas en el contrato pero NO implementadas: {missing}"


def test_every_contract_query_and_header_param_is_honored() -> None:
    contract, impl = _contract_ops(), _app_ops()
    problems: dict[str, list[str]] = {}
    for key, declared in contract.items():
        if key not in impl:
            continue
        missing = declared - impl[key]
        if missing:
            path, method = key
            problems[f"{method.upper()} {path}"] = sorted(f"{n} ({loc})" for n, loc in missing)
    assert not problems, f"Parámetros/cabeceras declarados pero NO implementados: {problems}"
