# Integración cross-service (golden paths) — RNF-32

Pruebas de integración **microservicio↔microservicio** en vivo, de extremo a extremo, sobre
`docker-compose.dev.yml` (ambos servicios reales, broker/BD/IdP reales, JWT reales). Complementan
—no sustituyen— los tests por servicio y el contract testing Pact (RNF-21, que valida el contrato en
aislamiento): aquí se verifica el **cableado vivo** del sistema ensamblado.

## Obligatorio (RNF-32, L-QA-09, DoD #8)

- **Cada vector de interacción** entre dos microservicios (evento producido→consumido, o llamada API)
  se valida con su **golden path**. Si un servicio interactúa con **varios**, hay **un golden path por
  cada contraparte**.
- Todos los vectores se registran en [`matriz_interaccion.md`](matriz_interaccion.md).
- Cada golden path se **diseña antes de ejecutar** (desde `templates/qa/golden_path_TEMPLATE.md` del
  repo management) y se llena con los **resultados** al ejecutar.
- Un vector cuya **contraparte aún no existe** = `DIFERIDO` (con disparador: se valida al construirse).

## Gatekeeper

`../../scripts/check_golden_paths.py` (en el **gate de release**, PR a `main`): lee la matriz y **falla**
si algún vector con **ambos** servicios construidos (`pom.xml`/`pyproject.toml`) no tiene golden path
`EJECUTADO`. En `develop`/ramas los golden paths en curso son legítimos.

```bash
python scripts/check_golden_paths.py   # desde la raíz del repo backend
```

## Golden paths (Fase A)

| Vector | Estado | Documento |
|---|---|---|
| `asset-inventory` → `config-backup` (`asset.*`) | ✅ EJECUTADO | [golden_path_asset_config-backup.md](golden_path_asset_config-backup.md) |
| `asset-inventory` → `compliance-audit` (`asset.*`) | 🔵 DIFERIDO | — |
| `config-backup` → `alerting` (`config.*`) | 🔵 DIFERIDO | — |
| `compliance-audit` → `alerting` (`compliance.finding_created`) | 🔵 DIFERIDO | — |
| `alerting` → `notification` (`alert.created`) | 🔵 DIFERIDO | — |
