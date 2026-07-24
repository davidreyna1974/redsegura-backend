#!/usr/bin/env python3
"""Gate anti-⏳ (DoD #1, lección L-QA-08): un microservicio no está "done" mientras su
``casos_de_prueba.md`` tenga casos en ⏳ PENDIENTE.

Cuenta las celdas de estado ``| ⏳ |`` en las tablas de casos (no la leyenda, que menciona ⏳ como
texto). Falla (exit 1) si encuentra alguna. Sin dependencias externas: corre en cualquier runner.

Uso:
    python scripts/check_casos_completos.py <ruta_a_casos_de_prueba.md> [más rutas...]
    python scripts/check_casos_completos.py --all      # todos los servicios del backend

Se usa como **gate de release** (PR a main): liberar un servicio exige 0 casos pendientes. Durante
el desarrollo en ramas/develop los ⏳ son legítimos (los casos se definen antes de codificar)."""

from __future__ import annotations

import glob
import re
import sys

# Una celda de estado pendiente en una fila de tabla: "... | ⏳ |" al final de la línea.
_PENDING_CELL = re.compile(r"\|\s*⏳\s*\|")


def pending_in(path: str) -> list[int]:
    """Devuelve los números de línea (1-based) con un caso en ⏳."""
    with open(path, encoding="utf-8") as fh:
        return [i for i, line in enumerate(fh, 1) if _PENDING_CELL.search(line)]


def main(argv: list[str]) -> int:
    if argv == ["--all"]:
        paths = sorted(glob.glob("*/documentos/casos_de_prueba.md"))
    else:
        paths = argv
    if not paths:
        print("uso: check_casos_completos.py <casos_de_prueba.md ...> | --all", file=sys.stderr)
        return 2

    failed = False
    for path in paths:
        lines = pending_in(path)
        if lines:
            failed = True
            print(f"❌ {path}: {len(lines)} caso(s) en ⏳ PENDIENTE (líneas {lines}).")
        else:
            print(f"✅ {path}: 0 casos pendientes.")
    if failed:
        print(
            "\nUn microservicio no está 'done' con casos en ⏳ (DoD #1). "
            "Implementa/valida cada caso o justifícalo como N/A antes de liberar.",
            file=sys.stderr,
        )
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
