# Matriz de interacción entre microservicios (vectores)

> **Fuente de verdad de los vectores de interacción** microservicio↔microservicio y su cobertura de
> **golden path** (integración cross-service en vivo, **RNF-32**). La **obliga** el gatekeeper
> `scripts/check_golden_paths.py` (gate de release): **ningún vector con ambas partes ya construidas
> puede quedar sin golden path ejecutado**. Vectores cuya contraparte aún no existe → **diferido con
> disparador** (se validan al construirse la contraparte).

**Regla de completitud:** cada vez que se añade un evento/consumidor (catálogo de eventos) o una
llamada API entre servicios, se **añade su fila aquí** antes de codificar (lo exige la
`propuesta_modulo` del servicio consumidor/llamador). Un vector = una dirección (productor→consumidor
o llamador→llamado); si un servicio interactúa con varios, hay **una fila por cada** contraparte.

## Formato (parseado por el gate)

Columnas fijas: `Origen` · `Destino` · `Tipo` · `Mecanismo` · `Estado` · `Golden path`.
- `Origen`/`Destino`: **directorio del servicio** en `backend/` (p. ej. `asset-inventory-service`).
- `Estado`: `EJECUTADO` (golden path corrido con resultados) · `DIFERIDO` (contraparte no existe aún).
- `Golden path`: archivo en `documentos/integracion/` (relativo) o `—` si diferido.

| Origen | Destino | Tipo | Mecanismo | Estado | Golden path |
|---|---|---|---|---|---|
| asset-inventory-service | config-backup-service | evento | `asset.*` (RabbitMQ, exchange `redsegura.events`) | EJECUTADO | golden_path_asset_config-backup.md |
| asset-inventory-service | compliance-audit-service | evento | `asset.*` (RabbitMQ) | DIFERIDO | — |
| config-backup-service | alerting-service | evento | `config.*` (RabbitMQ) | DIFERIDO | — |
| compliance-audit-service | alerting-service | evento | `compliance.finding_created` (RabbitMQ) | DIFERIDO | — |
| alerting-service | notification-service | evento | `alert.created` (RabbitMQ) | DIFERIDO | — |

> **Disparador de los DIFERIDO:** cada uno pasa a `EJECUTADO` **en cuanto ambos servicios existen**
> (el gate lo exigirá automáticamente: al aparecer el directorio de la contraparte, un vector
> `DIFERIDO` con ambos directorios presentes **hace fallar el gate** hasta documentar+ejecutar su
> golden path). Fase B añadirá sus propios vectores (p. ej. `scan-orchestrator`→`vulnerability`).

## Estado actual (Fase A)

- **1/5 vectores EJECUTADO** (`asset-inventory`→`config-backup`) — único con ambas partes construidas.
- **4/5 DIFERIDO** — esperan a `compliance-audit`, `alerting`, `notification`.
