# Prueba de integración (golden path) — `asset-inventory` → `config-backup`

> **Tipo:** prueba de **integración cross-service** end-to-end sobre el artefacto desplegado
> (`docker-compose.dev.yml`), con **ambos microservicios reales**, **broker real** (RabbitMQ),
> **bases de datos reales** (una por servicio) y **JWT reales** de Keycloak. Complementa —no
> sustituye— los tests unit/integración por servicio y los **contratos Pact** (que verifican el
> contrato en aislamiento); esta prueba verifica el **cableado vivo** de extremo a extremo.
>
> **Estado:** ✅ **EJECUTADO — ÉXITO** (2026-07-25): 6/6 pasos + 5/5 transversales, 0 hallazgos.
> Ver §12. Documento **vivo**. **Fecha de diseño y ejecución:** 2026-07-25.

## 1. Objetivo

Validar que, en un entorno equivalente a producción, la **interacción real entre `asset-inventory` y
`config-backup`** funciona de extremo a extremo: que un cambio en el **inventario** (alta/edición/baja
de un dispositivo, vía REST) se **propaga por eventos** hasta que `config-backup` puede **operar sobre
ese dispositivo** (respaldarlo), y que los cambios posteriores se reflejan — todo **sin acoplamiento
directo** entre los servicios.

## 2. Problema que resuelve

Los tests por servicio y los contratos Pact validan cada pieza **en aislamiento** (con dobles o
contra un esquema compartido). **Ninguno prueba el sistema ensamblado**: que el evento que **produce**
un servicio, publicado por **su** relay a un broker **real**, es **enrutado** por el exchange correcto
y **consumido y proyectado** por el **otro** servicio real, con sus dos bases de datos independientes,
su seguridad, su serialización y su red entre contenedores. Es la clase de fallo que solo aparece al
integrar (nombres de exchange/routing distintos, envelope divergente, campos que no casan, colas mal
ligadas, consistencia eventual que no converge). Esta prueba cierra ese hueco de **cableado vivo**.

## 3. Justificación

- El **golden path de extremo a extremo en Docker Compose** es un hito explícito del proyecto (backend
  `CLAUDE.md` §Próximos pasos).
- `config-backup` es el **primer consumidor** de `asset.*`: es el primer momento en que existe el par
  productor↔consumidor y, por tanto, en que la interacción puede probarse de verdad (antes solo había
  Pact consumidor contra un esquema).
- Refuerza, con evidencia viva, los RNF de comunicación (RNF-21 Pact, RNF-30 entrega garantizada,
  RNF-E1 idempotencia) y el patrón arquitectónico (database-per-service, event-driven).

## 4. Estándares / buenas prácticas que se verifican

| Estándar / patrón | Qué implica en esta prueba |
|---|---|
| **Arquitectura orientada a eventos (coreografía, no orquestación)** | Los servicios reaccionan a eventos; **nadie llama por HTTP al otro**. Desacoplamiento temporal y de despliegue. |
| **Database-per-service** | `config-backup` **no** consulta la BD de `asset-inventory`; mantiene su **propia proyección** (vista de dispositivos) alimentada por eventos. |
| **Transactional outbox** (ADR-04, RNF-30) | El evento se escribe en la **misma transacción** que el cambio de negocio; un relay lo publica con **publisher confirms** (entrega garantizada, at-least-once). |
| **Consumidor idempotente** (RNF-E1) | Deduplicación por `eventId`; reprocesar un evento no duplica ni corrompe la proyección (seguro con at-least-once). |
| **Contrato de eventos compartido + Pact** | Productor y consumidor validan contra el **mismo** `asset-event.schema.json` (sobre §3.3 + payload §4.1). |
| **Consistencia eventual** | La vista de `config-backup` **converge** al estado del inventario tras procesar el evento (no instantáneo, sí acotado). |
| **Read model / CQRS-ish** | `config-backup` mantiene una **proyección de lectura** del inventario, optimizada para su necesidad (saber a qué dispositivos conectarse). |
| **Seguridad de extremo a extremo** | RBAC validado en cada servicio con **JWT reales** de Keycloak; no se confía en el gateway. |
| **Zero-trust del scope de red (RNF-07)** | El respaldo solo intenta SSH a IPs dentro de los CIDRs autorizados (red simulada en dev). |

## 5. Topología de la interacción

```
Cliente (Postman/curl, JWT)
      │  REST POST/PUT/DELETE /api/v1/devices
      ▼
┌─────────────────────┐   asset.*     ┌──────────────────────┐   config.*
│  asset-inventory     │──(outbox+     │  RabbitMQ             │──(bind        (a alerting, futuro)
│  (Java, :8081)       │   relay)─────▶│  exchange topic       │   q.alerting…)
│  BD: asset_inventory │  publisher    │  redsegura.events     │
└─────────────────────┘  confirms     └──────────┬───────────┘
                                                  │ routing asset.* → q.config-backup.asset-events
                                                  ▼
                                       ┌──────────────────────┐
                                       │  config-backup        │  proyecta el dispositivo en su
                                       │  (Python, :8082)      │  vista local (BD config_backup) y
                                       │  BD: config_backup    │  luego lo respalda (SSH→Git→config.*)
                                       └──────────────────────┘
```

- **Exchange:** `redsegura.events` (topic, durable) — declarado por ambos de forma idempotente.
- **Routing keys (productor asset-inventory):** `asset.created`, `asset.updated`, `asset.decommissioned`.
- **Cola (consumidor config-backup):** `q.config-backup.asset-events` (durable, con DLQ) ligada a `asset.*`.
- **Sobre común (§3.3):** `{eventId, eventType, version, occurredAt, source, payload}`.

## 6. Precondiciones

1. `docker compose -f docker-compose.dev.yml up -d --build postgres rabbitmq keycloak asset-inventory config-backup`.
2. Postgres con **dos** bases: `asset_inventory` y `config_backup` (database-per-service).
3. Keycloak sembrado (realm `redsegura`, usuarios admin/operador/auditor, cliente `redsegura-postman`).
4. Ambos servicios `healthy` (`:8081/actuator/health`, `:8082/api/v1/health/readiness`).
5. JWT de `admin` (rol ADM) obtenido por password grant.

## 7. Pasos de la interacción (cómo funciona · qué se espera · cómo se verifica · resultado)

### Paso 1 — Alta de dispositivo en el inventario (REST → `asset-inventory`)
- **Cómo funciona:** el cliente hace `POST /api/v1/devices` a `asset-inventory` con JWT (ADM/OPE) y un
  `DeviceCreateRequest`. El servicio valida (RBAC server-side + reglas de negocio), persiste el
  dispositivo en su BD y, en la **misma transacción**, escribe un evento `asset.created` en su outbox.
- **Qué se espera:** `HTTP 201`; cuerpo con `id` (UUID) + campos; header `ETag`; una fila en la outbox
  de `asset-inventory` con `event_type = asset.created`.
- **Cómo se verifica:** `curl` (código 201 + `id`); *(opcional)* `SELECT` en `asset_inventory.outbox_events`.
- **Resultado (2026-07-25):** ✅ **HTTP 201**, `id=a63f8707-…`, `ETag="0"`. Dispositivo persistido.

### Paso 2 — Publicación del evento (outbox relay `asset-inventory` → RabbitMQ)
- **Cómo funciona:** el `OutboxRelay` (@Scheduled, `SKIP LOCKED`, **publisher confirms**) toma el evento
  pendiente y lo publica al exchange `redsegura.events` con routing key `asset.created` y el sobre
  común. Marca `published_at` **solo tras el ACK** del broker (RNF-30).
- **Qué se espera:** el evento queda con `published_at` no nulo; el mensaje entra al exchange.
- **Cómo se verifica:** `SELECT published_at` en `asset_inventory.outbox_events`; *(opcional)* contadores
  de publish en la RabbitMQ management API.
- **Resultado (2026-07-25):** ✅ fila `asset.created` en la outbox de `asset-inventory` con
  `published_at` **no nulo** (`asset.created|t`) → publicada al broker tras ACK.

### Paso 3 — Enrutamiento y entrega (RabbitMQ topic → cola de `config-backup`)
- **Cómo funciona:** el topic exchange enruta `asset.created` (match `asset.*`) a la cola durable
  `q.config-backup.asset-events`. La cola tiene DLQ para mensajes no procesables.
- **Qué se espera:** el mensaje llega a la cola del consumidor; **no** va a la DLQ.
- **Cómo se verifica:** RabbitMQ management API (mensajes entregados a la cola / DLQ vacía); o de forma
  indirecta por el éxito del Paso 4.
- **Resultado (2026-07-25):** ✅ cola `q.config-backup.asset-events` presente y **durable**; **DLQ = 0**
  (nada quedó sin procesar). La cola queda en 0 mensajes porque el consumidor los procesó al instante.

### Paso 4 — Consumo y proyección (`config-backup`)
- **Cómo funciona:** el consumidor de `config-backup` recibe el mensaje, deserializa el sobre, **deduplica
  por `eventId`** (idempotencia, tabla `processed_events`) y **proyecta** el dispositivo en su vista
  local (`config_backup.devices`). Hace ACK al broker.
- **Qué se espera:** el dispositivo aparece en `config_backup.devices` con `hostname`, `mgmt_ipv4`,
  `status = ACTIVO`; el `eventId` queda en `processed_events`.
- **Cómo se verifica:** `SELECT` en `config_backup.devices` filtrando por `device_id`.
- **Resultado (2026-07-25):** ✅ dispositivo proyectado en `config_backup.devices`:
  `hostname=E2E-CORE-SW · mgmt_ipv4=10.0.0.77 · status=ACTIVO` (convergió en < 10 s). `processed_events`
  registró el `eventId` (dedup). **Confirma la propagación cross-service por eventos, sin HTTP directo.**

### Paso 5 — Operación sobre el dispositivo proyectado (`config-backup` cierra el golden path)
- **Cómo funciona:** ya que `config-backup` "conoce" el dispositivo, `POST /api/v1/backups`
  (`scope=all`) lo **resuelve** y encola un job; el respaldo intenta SSH (en dev **no hay equipo real**
  → termina `FAILED` con gracia, RF-10) y emite `config.backup_failed` (sobre §4.3) por su propio
  outbox→relay.
- **Qué se espera:** `202` + `jobId`; el job llega a `COMPLETED` incluyendo el dispositivo
  (`outcome=FAILED` por SSH); el evento `config.backup_failed` se publica **con sobre conforme**.
- **Cómo se verifica:** `curl` (202 + `GET /backups/jobs/{id}`); inspección de una cola ligada a
  `config.*` en el broker (sobre completo + payload §4.3).
- **Resultado (2026-07-25):** ✅ `POST /backups scope=all` → **HTTP 202** + `jobId`; job **COMPLETED**
  `total=1 failed=1`, `outcome=FAILED` (SSH sin equipo real, RF-10 con gracia). `config-backup` **operó
  sobre el dispositivo que llegó por eventos desde `asset-inventory`** — golden path cerrado.

### Paso 6 — Propagación de cambios (edición y baja)
- **Cómo funciona:** `PUT`/`PATCH` en `asset-inventory` → `asset.updated` → `config-backup` actualiza la
  vista (hostname/IP). `DELETE` (baja) → `asset.decommissioned` → `config-backup` marca `status=BAJA`
  (RN-CB6); a partir de ahí `scope=all` **deja de incluirlo**.
- **Qué se espera:** la vista de `config-backup` refleja el cambio; el dado de baja se excluye de
  `scope=all`.
- **Cómo se verifica:** `curl` (`PUT`/`DELETE` en `asset-inventory`, con `If-Match`/ETag); `SELECT` en
  `config_backup.devices`; `POST /backups scope=all`.
- **Resultado (2026-07-25):** ✅ **Edición:** `PUT` (con `If-Match`) → **HTTP 200**; `config_backup`
  actualizó `hostname` a `E2E-CORE-SW-RENAMED` (`asset.updated` propagado). ✅ **Baja:** `DELETE` →
  **HTTP 204**; `config_backup` marcó `status=BAJA` (RN-CB6, `asset.decommissioned`) y el siguiente
  `POST /backups scope=all` resolvió **total=0** → el dado de baja **queda excluido**.

## 8. Verificaciones transversales (propiedades del sistema)

| ID | Propiedad | Cómo se verifica | Resultado |
|---|---|---|---|
| GP-X1 | **Desacoplamiento** (sin HTTP directo entre servicios) | `config-backup` no tiene cliente HTTP a `asset-inventory`; su única fuente del inventario es el stream de eventos | ✅ **0** referencias HTTP salientes a `asset-inventory` en el código; única fuente = consumidor de eventos (`messaging/`) |
| GP-X2 | **Database-per-service** | `config-backup` nunca consulta la BD `asset_inventory`; opera sobre su propia proyección | ✅ dos BD independientes (`asset_inventory`, `config_backup`); `config-backup` solo lee/escribe la suya |
| GP-X3 | **Idempotencia del consumidor** | Reenviar el mismo `eventId` no duplica ni pisa un update posterior | ✅ publicado **2×** el mismo `eventId` → `processed_events` **+1 (no +2)**, **1** proyección |
| GP-X4 | **Entrega garantizada / resiliencia** | Cola durable (at-least-once); outbox + publisher confirms | ✅ `q.config-backup.asset-events` + DLQ **durables**; DLQ vacía. (Resiliencia de reconexión ya probada en `HALLAZGO-LIVE-CBS-01`) |
| GP-X5 | **Consistencia eventual acotada** | La proyección converge en un tiempo razonable (< ~10 s en dev) | ✅ convergió dentro de la ventana de sondeo (< 10 s) en alta, edición y baja |

## 9. Criterios de éxito (checklist)

- [x] **Paso 1:** alta REST → `201` + `id` + `ETag` en `asset-inventory`.
- [x] **Paso 2:** `asset.created` publicado (`published_at`) con sobre §3.3.
- [x] **Paso 3:** mensaje entregado a `q.config-backup.asset-events`; DLQ vacía.
- [x] **Paso 4:** dispositivo proyectado en `config_backup.devices` (< ~10 s) con datos correctos.
- [x] **Paso 5:** `config-backup` opera sobre el dispositivo (job lo resuelve; emite `config.*` conforme).
- [x] **Paso 6:** edición y baja se propagan; el dado de baja se excluye de `scope=all`.
- [x] **Transversales:** GP-X1..X5 satisfechas.
- [x] **0 regresiones** en los servicios (ambos siguen certificados; gates verdes).

**Todos los criterios ✅.**

## 10. Fuera de alcance (registrado)

- **SSH exitoso contra un dispositivo real:** en dev no hay equipo; el respaldo termina `FAILED` con
  gracia (RF-10). El camino SSH exitoso se cubre con dobles (tests por servicio) y se validará en
  PRE-REL contra red emulada real (GNS3/Containerlab) — `preparacion_produccion.md §2.9`.
- **Consumidores de `config.*`** (`alerting`): aún no existen; su golden path se documentará al construirlos.
- **Prueba de carga / latencia de propagación bajo volumen** (RNF-01/02): PRE-REL.

## 11. Reproducción

```bash
# 1. Levantar el stack completo (backend/)
docker compose -f docker-compose.dev.yml up -d --build postgres rabbitmq keycloak asset-inventory config-backup
# 2. Token de admin
curl -s -X POST http://localhost:8080/realms/redsegura/protocol/openid-connect/token \
  -d grant_type=password -d client_id=redsegura-postman -d username=admin -d password=password | jq -r .access_token
# 3. Alta en asset-inventory (:8081) → observar la proyección en config-backup (:8082 / BD config_backup)
# (pasos 1..6 arriba). Colecciones Postman por servicio en <servicio>/postman/.
```

## 12. Resultados de la ejecución

- **Fecha:** 2026-07-25 · **Entorno:** `docker-compose.dev.yml` (imágenes reconstruidas de `develop`;
  `asset-inventory` Java y `config-backup` Python, ambos certificados; RabbitMQ + Keycloak + Postgres
  con las dos BD).
- **Resultado global: ✅ ÉXITO — 6/6 pasos + 5/5 transversales, 0 hallazgos, 0 regresiones.**

| Paso | Resultado |
|---|---|
| 1 — Alta REST | ✅ 201 + `id` + `ETag` |
| 2 — Outbox + publicación | ✅ `asset.created` con `published_at` (confirms) |
| 3 — Enrutamiento | ✅ entregado a la cola; DLQ vacía |
| 4 — Consumo + proyección | ✅ dispositivo en `config_backup.devices` < 10 s; dedup registrado |
| 5 — Operación (respaldo) | ✅ 202 + job COMPLETED; `config-backup` respaldó el dispositivo (SSH FAILED con gracia) |
| 6 — Edición + baja | ✅ `asset.updated` renombró; `asset.decommissioned` → BAJA + excluido de `scope=all` |
| GP-X1..X5 | ✅ desacoplamiento · DB-per-service · idempotencia (+1 no +2) · durabilidad · consistencia eventual |

**Veredicto:** la interacción real `asset-inventory` ↔ `config-backup` (alta/edición/baja del
inventario → propagación por eventos → proyección y operación en `config-backup`) funciona de extremo
a extremo sobre el artefacto desplegado, respetando los estándares del §4. **Sin hallazgos.** Es la
primera evidencia viva del **golden path event-driven** de Fase A; se extenderá a `compliance`,
`alerting` y `notification` a medida que se construyan (cada uno con su golden path documentado).
