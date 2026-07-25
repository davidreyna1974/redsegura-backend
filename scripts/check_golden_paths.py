#!/usr/bin/env python3
"""Gate de integración cross-service (RNF-32, lección L-QA-09): **ningún vector de interacción entre
dos microservicios ya construidos puede quedar sin golden path ejecutado**.

Lee `documentos/integracion/matriz_interaccion.md` (la fuente de verdad de los vectores) y, para cada
fila:
  - Si **ambos** servicios (Origen y Destino) existen en `backend/<servicio>/` → el vector es
    exigible: su `Estado` debe ser `EJECUTADO` y su `Golden path` debe existir en
    `documentos/integracion/` y estar marcado `EJECUTADO` en el propio documento. Falla si no.
  - Si falta algún servicio → el vector es legítimamente `DIFERIDO` (contraparte no construida).

Se ejecuta en el gate de release (PR a `main`): un servicio no es production-ready mientras deje un
vector de interacción con otro servicio existente sin validar en vivo.

Uso:  python scripts/check_golden_paths.py   (desde la raíz del repo backend)
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MATRIX = ROOT / "documentos" / "integracion" / "matriz_interaccion.md"
GP_DIR = ROOT / "documentos" / "integracion"
_ROW = re.compile(r"^\|\s*([a-z0-9-]+-service)\s*\|\s*([a-z0-9-]+-service)\s*\|")


def _service_built(name: str) -> bool:
    """Un servicio está **construido** (no solo un placeholder de contrato de la fundación) si tiene
    un archivo de build real: ``pom.xml`` (Java) o ``pyproject.toml`` (Python). Tener solo
    ``openapi.yaml`` = contrato definido pero servicio sin implementar → su vector es DIFERIDO."""
    d = ROOT / name
    return (d / "pom.xml").exists() or (d / "pyproject.toml").exists()


def _cells(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def main() -> int:
    if not MATRIX.exists():
        print(f"❌ No existe la matriz de interacción: {MATRIX}", file=sys.stderr)
        return 2

    problems: list[str] = []
    checked = 0
    deferred = 0
    for line in MATRIX.read_text(encoding="utf-8").splitlines():
        if not _ROW.match(line):
            continue
        cells = _cells(line)
        if len(cells) < 6:
            continue
        origen, destino, _tipo, _mec, estado, gp = cells[:6]

        if not (_service_built(origen) and _service_built(destino)):
            deferred += 1
            continue  # contraparte no construida → DIFERIDO legítimo

        # Ambos servicios existen → el vector es EXIGIBLE.
        checked += 1
        vector = f"{origen} → {destino}"
        if estado.upper() != "EJECUTADO":
            problems.append(
                f"{vector}: ambos servicios existen pero el estado es '{estado}' "
                f"(debe ser EJECUTADO — RNF-32)."
            )
            continue
        gp_path = GP_DIR / gp
        if gp == "—" or not gp_path.exists():
            problems.append(f"{vector}: falta el documento de golden path '{gp}'.")
            continue
        if "EJECUTADO" not in gp_path.read_text(encoding="utf-8").upper():
            problems.append(
                f"{vector}: el golden path '{gp}' no está marcado como EJECUTADO (¿sin resultados?)."
            )

    print(f"Vectores exigibles verificados: {checked} · diferidos (contraparte no construida): {deferred}")
    if problems:
        print("\n❌ Vectores de interacción sin golden path ejecutado (RNF-32):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        print(
            "\nCada interacción microservicio↔microservicio entre servicios existentes debe "
            "validarse en vivo (golden path). Documenta+ejecuta el golden path o corrige la matriz.",
            file=sys.stderr,
        )
        return 1
    print("✅ Todos los vectores exigibles tienen golden path ejecutado.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
