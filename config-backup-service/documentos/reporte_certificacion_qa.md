# Reporte de certificación QA (4 fases) — config-backup-service

> Ronda formal bajo el [Protocolo de verificación en 4 fases](../../../management/documentos/qa/protocolo_verificacion_4_fases.md).
> **Regla inamovible:** la ronda se ejecuta íntegra sobre código **congelado**; si se corrige un bug,
> se re-ejecuta desde cero (Fase 3). No sustituye R1/R2; las **certifica**.
>
> **Rondas de certificación (histórico, más reciente primero):**
> - **R-C3** (3ª vuelta, 2026-07-24, commit `81bd1c6`) — re-certificación tras cerrar la conformidad
>   de contrato de **eventos** (`HALLAZGO-EVT-CBS-01`). **✅ CERTIFICADO, 0 hallazgos.**
> - **R-C2** (2ª vuelta, 2026-07-24, commit `ceb48a8`) — re-certificación tras los gates de prevención
>   + aceptación BDD. **✅ CERTIFICADO, 0 hallazgos.**
> - **R-C1** (1ª vuelta, 2026-07-24, commit `13e3106`) — 1ª certificación; detectó y corrigió
>   `HALLAZGO-QA-CBS-01/02`. **✅ CERTIFICADO.**

---

# Ronda R-C3 — 3ª vuelta (2026-07-24)

> **Motivación:** al cerrar la 3.ª cara del contrato (eventos producidos) se corrigió
> `HALLAZGO-EVT-CBS-01` (ver §"HALLAZGO-EVT-CBS-01" abajo) — un **cambio de la superficie emitida** que
> invalidó la base congelada de R-C2. Se re-certifica desde cero, todos los elementos partiendo de "no
> validado". No sobrescribe R-C1/R-C2.

- **Inicio de ronda (Fase 1):** commit congelado `81bd1c6` · **Fecha:** 2026-07-24

## R-C3 · Fase 1 — Inventario (código congelado)

- **Gate limpio (cachés purgadas):** ruff ✅ · mypy ✅ (65 archivos) · **105 tests, 0 fallos** · cobertura **95 %**.
- **Gate anti-⏳:** ✅ 0 pendientes.
- **Conformidad de contrato — API:** 13/13 operaciones · 0 parámetros/cabeceras sin honrar.
- **Conformidad de contrato — eventos:** esquema válido; los **4** `config.*` emitidos validan el
  `config-event.schema.json` (`test_config_event_contract`, happy/sad).
- **Inventario de casos:** **68 ✅ · 0 ⏳ · 0 ❌** (incluye EVT-CONF-01..05).
- **Conclusión:** **0 hallazgos.**

## R-C3 · Fase 2 — Corrección

**Sin correcciones.** Código permanece congelado.

## R-C3 · Fase 3 — Re-ejecución completa

- **Gate limpio:** 105 tests · cobertura 95 % · ruff+mypy verdes.
- **Verificación en vivo** (imagen reconstruida, JWT reales de Keycloak): batería estándar **20/20** +
  **13/13** (idempotencia/filtros) + **envelope de eventos en vivo**: `config.backup_failed` observado
  en el broker con sobre completo (`eventId/eventType/version=1.0.0/occurredAt/source` + payload §4.3).
  **0 hallazgos · 0 regresiones.**

## R-C3 · Fase 4 — Certificación

- Gate final ✅ · anti-⏳ ✅ · conformidad API ✅ · conformidad eventos ✅.
- Casos: **68 ✅ · 0 ⏳ · 0 ❌**. Matriz RNF sin 🟡 DEV (RNF-21 produce/consume ambos 🟢).
- **Veredicto R-C3: ✅ CERTIFICADO** (0 hallazgos). Las **3 caras del contrato** (API, eventos
  consumidos, eventos producidos) tienen gate ejecutable y están verificadas.

## HALLAZGO-EVT-CBS-01 — eventos `config.*` no conformes al catálogo (post-R-C2, corregido)

**Detección:** al cerrar la deuda "esquema de eventos producidos" (registrada en la auditoría
documental), se comparó lo emitido contra el catálogo (§3.3 sobre, §4.2–4.5 payloads) y se halló:
1. **Sin sobre común:** se publicaba el **payload desnudo** (`{"deviceId":…}`), sin
   `eventId/eventType/version/occurredAt/source`. Un consumidor real (`alerting`) no podría procesarlo.
2. **Payloads divergentes** en los 4 eventos: faltaban `capturedAt`/`status` (completed),
   `attemptedAt` y nombre `reason`≠`failureReason` (failed), `baselineBackupId`/`detectedBy` y nombre
   `detectedAt`≠`checkedAt` (drift), `runningVsStartupDiffRef`/`detectedAt` (unsaved).

**Por qué ningún gate/ronda lo cazó:** era la **tercera cara del contrato** (eventos producidos), sin
esquema formal contra el cual comparar; el gate de conformidad (Pieza 1) solo cubre la API, y los
tests EVT-OUT solo verificaban el `event_type`, no la estructura del mensaje publicado. Es la misma
raíz de siempre — *estándar sin artefacto/gate que lo obligue* — en el eje de eventos.

**Corrección:** sobre común construido al publicar (`outbox.to_envelope`, relay); payloads alineados
al catálogo; **JSON Schema** `contracts/events/config-event.schema.json`; **test de conformidad
productor-side** `test_config_event_contract` (happy/sad, con dientes); `test_broker` valida el sobre
sobre RabbitMQ real. **Verificación en vivo:** envelope real observado en el broker
(`eventId/eventType/version=1.0.0/source=config-backup-service` + payload §4.3). **Blast radius:**
local al productor de eventos (el contrato de evento se formaliza ahora; no hay consumidor aún).
Gate: **105 tests, cobertura 95 %**, ruff+mypy verdes. → **Pendiente formalizar en R-C3.**

---

# Ronda R-C2 — 2ª vuelta (2026-07-24)

> **Motivación:** tras R-C1 se añadieron cambios (aditivos, sin tocar la lógica de endpoints): los
> **3 gates de prevención** (test de conformidad contrato↔impl, gate anti-⏳, reglas de templates/DoD)
> y la **batería de aceptación BDD** (pytest-bdd). Por la regla inamovible, el código cambió → se
> re-certifica desde cero, con **todos los elementos partiendo de "no validado"**. No sobrescribe R-C1
> (documentada más abajo).

- **Inicio de ronda (Fase 1):** commit congelado `ceb48a8` · **Fecha:** 2026-07-24

## R-C2 · Fase 1 — Inventario (código congelado)

- **Gate limpio (cachés purgadas):** ruff ✅ · mypy ✅ (64 archivos) · **99 tests, 0 fallos** · cobertura **95 %**.
- **Gate anti-⏳:** ✅ 0 casos pendientes.
- **Conformidad de contrato (ejecutable):** 13/13 operaciones implementadas · 0 parámetros/cabeceras sin honrar.
- **Inventario de casos:** **63 ✅ · 0 ⏳ · 0 ❌ · 0 N/A** (incluye CONF-01/02 y ACPT-01/04 nuevos).
- **Conclusión:** **0 hallazgos.** Los cambios aditivos (gates + BDD) no regresionaron la superficie
  funcional; el contrato sigue honrado al 100 %.

## R-C2 · Fase 2 — Corrección

**Sin correcciones:** Fase 1 no arrojó hallazgos. Código permanece congelado.

## R-C2 · Fase 3 — Re-ejecución completa

- **Gate limpio:** ruff ✅ · mypy(strict) ✅ · **99 tests** · cobertura **95 %**.
- **Verificación en vivo** (imagen reconstruida sobre `docker-compose.dev.yml`, JWT reales de Keycloak):
  **batería estándar 20/20** + **13/13 de conformidad (idempotencia + filtros)**. Golden path
  event-driven ✅, RF-10 (SSH FAILED con gracia) ✅. **0 hallazgos · 0 regresiones.**

## R-C2 · Fase 4 — Certificación

- Gate final ✅ (99 tests, cobertura 95 %, ruff+mypy verdes) · anti-⏳ ✅ · conformidad ✅.
- Casos: **63 ✅ · 0 ⏳ · 0 ❌**. Matriz RNF sin 🟡 DEV. Contratos (Pact + `config.*`) verificados.
- **Veredicto R-C2: ✅ CERTIFICADO** (0 hallazgos). Refuerza R-C1 con los gates de prevención activos
  y la aceptación BDD. Commit `chore(qa)`.

---

# Ronda R-C1 — 1ª vuelta (2026-07-24)

- **Servicio:** config-backup-service · **Rama:** `develop`
- **Inicio de ronda (Fase 1):** commit congelado `13e3106`
- **Fecha:** 2026-07-24

---

## Fase 1 — Inventario (código congelado, sin corregir)

**Gate sobre código congelado:** ruff ✅ · mypy ✅ (58 archivos) · **76 tests, 0 fallos** · cobertura **94.97 %**.

**Inventario de casos** (`casos_de_prueba.md`): 47 ✅ · 9 ⏳ · 0 ❌ · 0 N/A.

El inventario destapó que **9 casos** seguían en ⏳ PENDIENTE. Clasificación:

### Hallazgos que requieren código (divergencia contrato↔implementación)
- **`HALLAZGO-QA-CBS-01` (ABIERTO → Fase 2):** `GET /backups` (operación `listBackups`) **ignora
  filtros declarados en `openapi.yaml`**: `hostname`, `mgmtIp`, `status`, `from`, `to` y `sort`. La
  implementación solo aplicaba `deviceId` y `unsavedChanges`. Un cliente que filtra por estado o rango
  de fechas recibía resultados sin filtrar (silenciosamente incorrectos). Caso **BSRCH-01**.
- **`HALLAZGO-QA-CBS-02` (ABIERTO → Fase 2):** el parámetro `Idempotency-Key` está declarado en el
  contrato para `POST /devices/{id}/backups`, `POST /backups` y `POST /schedules`, pero **no se
  honraba** — un reintento (p. ej. por timeout de red del cliente) creaba un job/schedule/respaldo
  **duplicado**. Caso **RN-CB7**.

### Casos ya cubiertos, pendientes solo de marcar (sin cambio de código)
- **HLTH-01/02/03:** cubiertos por `test_health.py` (liveness UP, readiness UP, readiness→503 DOWN).
- **VAL-02:** diff con refs inválidas → 400, verificado en vivo (R1/R2 #20).

### Gaps de cobertura de test (feature implementada, falta test dedicado)
- **CRUD-04** (diff running entre 2 commits), **RN-CB3b** (diff `configType=startup`),
  **EMPTY-01** (historial vacío → 200 lista vacía), **RES-01** (fallo/timeout SSH → `FAILED`, no cuelga).

**Conclusión Fase 1:** 2 hallazgos de código (ABIERTO) + gaps de test. El servicio **no certifica**
en este estado (DoD #1: todos los casos en ✅ PASS). Se procede a Fase 2. Código permanece congelado
hasta iniciar las correcciones.

---

## Fase 2 — Corrección + gatekeeper (blast radius por fix)

### `HALLAZGO-QA-CBS-01` — filtros de `GET /backups` (RESUELTO)
`list_backups` ahora honra todos los parámetros del contrato: `hostname` y `mgmtIp` (resueltos
contra la vista de dispositivos vía subconsulta), `status`, `from`/`to` (rango sobre `capturedAt`) y
`sort` (`campo,dir`; por defecto `capturedAt,desc`; campo desconocido → orden por defecto, no falla).
**Tests:** `test_backups_filters.py` (7: status, rango de fechas, hostname, mgmtIp, sort asc, sort
inválido, historial vacío → EMPTY-01). **Blast radius:** *local* — sin cambio de contrato (el
`openapi.yaml` ya los declaraba; la implementación ahora coincide) ni de eventos.

### `HALLAZGO-QA-CBS-02` — idempotencia de escritura (RESUELTO)
Nuevo módulo `app/idempotency.py` + tabla `idempotency_keys` (migración **0004**) + cabecera
`Idempotency-Key` en `POST /devices/{id}/backups`, `POST /backups` y `POST /schedules`. Se **reserva**
la clave (acotada por actor) antes del efecto; reintento con misma clave+cuerpo → replay de la
respuesta guardada sin duplicar; distinto cuerpo → **409**; fallo de negocio → libera la clave
(reintento posible). **Tests:** `test_idempotency.py` (8: no duplica, conflicto 409, claves distintas,
sin clave, acotado por actor, schedule, liberación tras fallo). **Blast radius:** *local* — sin cambio
de contrato (la cabecera ya estaba declarada); nueva tabla propia del servicio.

### Casos ya cubiertos, marcados PASS (sin cambio de código)
HLTH-01/02/03 (`test_health`), CRUD-04/RN-CB3b/VAL-02 (nuevo `test_diff_api.py`, 3 tests),
RES-01 (`test_backup_service` fallo persistente → FAILED).

**Gate tras Fase 2:** ruff ✅ · mypy ✅ · **93 tests** (76 → +17), 0 fallos · cobertura **95 %**.
Inventario de casos: **57 ✅ · 0 ⏳ · 0 ❌**. Ambos hallazgos con blast radius **local** → basta
re-probar este servicio en Fase 3. **Código re-congelado** para la re-ejecución.

---

## Fase 3 — Re-ejecución completa desde cero

Sobre el código **re-congelado** (con los fixes de Fase 2), cachés purgadas:

- **Gate limpio:** ruff ✅ · mypy ✅ (62 archivos) · **93 tests, 0 fallos** · cobertura **95 %**.
- **Verificación en vivo** (imagen reconstruida sobre `docker-compose.dev.yml`, JWT reales de Keycloak):
  - **Batería estándar 20/20** (salud, RBAC 401/403, validación 400/422, jobs async, drift,
    aislamiento de tipo 404, diff 400, golden path event-driven, RF-10 SSH FAILED con gracia).
  - **13/13 comprobaciones de los hallazgos corregidos:** idempotencia (mismo key+cuerpo → mismo
    `jobId`/`id` sin duplicar; distinto cuerpo → 409; schedule idempotente) y filtros de `GET /backups`
    (status, hostname, sort).

**0 hallazgos nuevos · 0 regresiones.** La ronda es válida (ejecutada íntegra sobre código congelado).

---

## Fase 4 — Certificación

- **Gate final:** ruff ✅ · mypy(strict) ✅ · **93 tests, 0 fallos** · cobertura **95 %** (umbral 70 %).
- **Inventario de casos:** **57 ✅ · 0 ⏳ · 0 ❌** — DoD #1 satisfecho.
- **Matriz RNF:** sin ningún 🟡 de etapa DEV.
- **Contratos:** consume `asset.*` (Pact/INT-CONS) + produce `config.*` (outbox/relay) — verificados.
- **Verificación en vivo:** ✅ (Fase 3).

**Veredicto: ✅ CERTIFICADO.** `config-backup-service` queda certificado bajo el Protocolo de 4 fases
(ronda de certificación 2026-07-24), con **2 hallazgos** detectados en Fase 1
(`HALLAZGO-QA-CBS-01` filtros de listado, `HALLAZGO-QA-CBS-02` idempotencia) **corregidos y
verificados**. Commit de certificación: `chore(qa)`.
