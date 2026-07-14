# Memoria técnica de módulo — asset-inventory-service

> Documento **vivo**: iniciado con el módulo (pre-código) y actualizado al cerrar cada fase.
> Memoria de "qué se hizo y por qué". Referencias: [`propuesta_modulo.md`](propuesta_modulo.md),
> [`casos_de_prueba.md`](casos_de_prueba.md), [`../openapi.yaml`](../openapi.yaml).

**Estado:** en desarrollo (pre-código) · **Última actualización:** 2026-07-13

## 1. Contexto y justificación
`asset-inventory-service` mantiene el **inventario único de activos de red** — la **fuente de
verdad** del sistema (RF-01..05). Es el primer microservicio de Fase A porque los demás
(`config-backup`, `compliance-audit`, y los de Fase B) dependen de conocer *qué* dispositivos
existen. Expone el inventario por API REST y publica eventos de dominio ante cada cambio.

## 2. Decisiones de diseño
| Decisión | Alternativas | Motivo |
|---|---|---|
| Java 21 / Spring Boot | Python | Lógica de negocio (RBAC, unicidad, estado) — dominio Java (ADR base) |
| **Contract-first** (openapi-generator) | Code-first | El `openapi.yaml` genera interfaces+DTOs; el controlador las implementa (ADR-05) |
| **MapStruct** entidad↔DTO | Mapeo manual | Type-safe en compilación; nunca exponer la entidad JPA (ADR-06) |
| **Identidad `serialNumber`** inmutable | Identidad por hostname/IP | hostname/IP son mutables; el serial es identidad estable (CMDB) |
| **Soft delete** (`status` ACTIVO/BAJA) | Borrado físico | Conserva historial y trazabilidad (RF-02) |
| **Bloqueo optimista** (`@Version`) expuesto como **ETag/If-Match** | Sin control de concurrencia | Evita *lost updates*; contrato HTTP estándar (ADR-09) |
| **Transactional outbox** | Publicar directo | Consistencia BD↔broker (ADR-04) |
| **JPA Auditing** (`created/updated_at/by`) | Auditoría manual | Patrón único auto-poblado desde el JWT (ADR-07) |
| **RFC 7807** / probes / redacción `mgmtIp` | Propietario | Estándares de industria (ADR-08/10/11) |
| **Location embebida** `{site,room,row,rack,rackUnit}` | Recursos Site/Rack aparte | DCIM suficiente para MVP; recursos separados → backlog |
| **Flyway** para el esquema | Cambios manuales | Migraciones versionadas (estándares §11) |

## 3. Componentes / interfaces / capas
Patrón `controller → service → repository` (estándares §3.1):
- **`DeviceController`** — implementa la interfaz generada del `openapi.yaml`; filtro JWT + autorización por rol.
- **`DeviceService`** (`@Transactional`) — reglas de negocio (RN1..RN11), soft delete, unicidad, publicación de eventos.
- **`DeviceRepository`** (JPA) — persistencia; índices únicos en `serialNumber`, `hostname`, `mgmtIp`.
- **`DeviceMapper`** (MapStruct) — entidad `Device` ↔ DTOs.
- **`Device`** (entidad) — extiende una `@MappedSuperclass Auditable`; `@Version` para concurrencia.
- **`OutboxPublisher`** — escribe el evento en la tabla outbox dentro de la transacción; un relay lo publica a RabbitMQ.
- **`MgmtIpRedactor`** — enmascara `mgmtIp` según el rol (ADR-11).
- **`IdempotencyRecord`/`IdempotencyRepository`** — persisten `(Idempotency-Key, usuario)` → hash del cuerpo + dispositivo creado.
- **`BulkImportJob`** — worker asíncrono de importación con resultado por dispositivo.

## 4. Contratos con dependencias
- **API propia:** `../openapi.yaml` (validado; gobernado por Spectral).
- **Eventos publicados** (verificados contra `comunicacion_por_eventos.md §4.1`):
  `asset.created` / `asset.updated` (+`changedFields`) / `asset.decommissioned`.
- **Identidad:** JWT (Keycloak), claim de rol `ADM`/`OPE`/`AUD`.
- **No consume** la API de ningún otro servicio (es la fuente de verdad).

## 5. Algoritmos y lógica no trivial
- **Unicidad robusta (RN1):** índice único en BD + captura de `DataIntegrityViolationException`
  → 409 (no basta un `SELECT` previo: hay carrera entre chequeo e inserción).
- **Concurrencia ETag↔`@Version` (RN8):** el `ETag` se deriva de `@Version`; en `PUT/PATCH/DELETE`
  se compara `If-Match` con la versión actual → 412 si difiere, 428 si falta la cabecera.
- **Soft delete + filtro por defecto (RN6):** los listados excluyen `status=BAJA` salvo filtro explícito.
- **Idempotencia (RN9):** clave acotada por usuario + hash SHA-256 del cuerpo. Misma clave y cuerpo →
  replay del original; misma clave con cuerpo distinto → 409 (evita replay de un recurso ajeno).
- **Transactional outbox (RN11):** el evento se escribe en la tabla outbox en la **misma** transacción
  que la mutación; un proceso relay lo publica → si la transacción no confirma, no hay evento.
- **Redacción `mgmtIp` (RN10):** para Auditor se enmascara en el servidor **antes** de serializar
  (el valor real nunca viaja); ADM/OPE lo ven completo.

## 6. Seguridad / RBAC del módulo
- **Escritura** (POST/PUT/PATCH/DELETE, bulk) → **ADM**. **Lectura** → ADM, OPE, AUD. Probes → público.
- **Redacción por rol:** `mgmtIp` enmascarada para Auditor (server-side, ADR-11).
- **Log de auditoría de seguridad:** accesos denegados (403), auth fallida (401) y mutaciones con actor.
- Autorización validada **en el servicio** (no se asume filtrado del Gateway).

## 7. Ejecución de tests (evidencia verificable)

**Hito 2 — persistencia (2026-07-13):** `mvn -pl asset-inventory-service verify` → BUILD SUCCESS.
```
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
Cobertura JaCoCo: LINE 93.5% · INSTRUCTION 95.5% · BRANCH 71.4%  (umbral 70% cumplido)
Checkstyle: 0 violaciones · Spotless: OK
```
Suites: `DeviceTest` (dominio, sin BD), `DeviceRepositoryIT` (integración con **PostgreSQL real**
vía Testcontainers + Flyway: auditoría, versión, unicidad de serial/hostname/mgmtIp),
`HealthControllerTest`, `AssetInventoryApplicationTests` (arranque de contexto).

**Hito 3 — web + servicio (2026-07-13):** `mvn -pl asset-inventory-service verify` → BUILD SUCCESS.
```
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
Cobertura JaCoCo: LINE 92.3% (umbral 70% cumplido) · BRANCH 56.2%
Checkstyle: 0 violaciones · Spotless: OK
```
Suites nuevas: `DeviceServiceIT` (RN1..RN7: alta, unicidad 409, no encontrado 404, edición parcial,
baja lógica excluida del listado, filtro por hostname) y `DeviceControllerIT` (stack completo:
201/200/404/409/422 en formato RFC 7807, paginación).

**Hito 4a — seguridad JWT/RBAC (2026-07-13):** OAuth2 Resource Server (Keycloak); RBAC por
endpoint (escritura solo ADM; lectura ADM/OPE/AUD; probes públicos); roles extraídos de
`realm_access.roles`; `created_by`/`updated_by` desde el JWT (o `system`). `mvn verify` → 28 tests
(incluye SEC-01 Auditor→403, SEC-02 sin token→401), cobertura ≥70%, 0 Checkstyle.

**Hito 4b — ETag/If-Match (2026-07-13):** concurrencia optimista sobre HTTP (ADR-09). El `ETag`
(del `@Version`) se devuelve en create/get/put/patch; las mutaciones exigen `If-Match` → 412 si no
coincide (o `ObjectOptimisticLockingFailureException` en carrera), 428 si falta. `mvn verify` → 32
tests (incluye FLOW-02 412, FLOW-03 428, PATCH feliz con ETag), cobertura ≥70%, 0 Checkstyle.

**Hito 4c — idempotencia (2026-07-13):** `POST` con `Idempotency-Key` (RN9). Tabla
`idempotency_keys` (Flyway V2); si la clave ya existe se devuelve el dispositivo original (replay,
sin duplicar); la clave y el dispositivo se guardan en la misma transacción.

**Hito 4c-bis — idempotencia endurecida (2026-07-13):** se eliminó la simplificación "global por
clave". Ahora la clave se **acota por usuario** (`created_by`, del sujeto JWT vía `AuditorAware`):
dos clientes distintos pueden usar la misma cadena sin colisionar (unicidad `(id_key, created_by)`).
Además se guarda el **SHA-256 del cuerpo canónico** (`request_hash`, serializado con Jackson):
misma clave + mismo cuerpo → replay; misma clave + **cuerpo distinto** → **409**
(`IDEMPOTENCY_KEY_CONFLICT`), en vez de reproducir silenciosamente un recurso ajeno a la petición.
`mvn verify` → 36 tests (replay + conflicto, a nivel servicio y API), cobertura ≥70%, 0 Checkstyle.
*Deuda restante:* TTL de expiración de claves y persistencia de la respuesta HTTP completa (hoy se
re-lee el dispositivo).

**Pendiente (Hito 4, resto):** redacción de `mgmtIp` (RN10), publicación de eventos vía outbox
(RN11), bulk import, y Pact de eventos `asset.*`. Nota: 401/403 aún no salen en `problem+json`
(requiere entry point/handler propios) — refinamiento.

**Hito 3b — contract-first estricto (ADR-05):** se cableó **openapi-generator**. El contrato genera
las interfaces de API (`DevicesApi`) y los DTOs; el `DeviceController` **implementa** la interfaz
generada, de modo que el código cumple el contrato **por construcción** (si el contrato cambia, no
compila). Se eliminaron los DTOs escritos a mano. Verificación: `mvn verify` → 26 tests, cobertura
LINE 86.7% (excluyendo generado), 0 Checkstyle.

> **Retos de entorno resueltos (documentados como lección):** (1) la ruta del proyecto tiene
> **espacios** y swagger-parser la trata como URI → se copia el spec a una ruta temporal sin
> espacios antes de generar; (2) openapi-generator **descarta** los parámetros a nivel-path cuando
> la operación define sus propios `parameters` → se declaró `deviceId` en cada operación;
> (3) la validación de formato de IP (RN5) se movió **al contrato** (`pattern`), para que la
> genere el DTO; (4) JaCoCo/Checkstyle excluyen el código generado.

> Nota de entorno: docker-java usa por defecto una API de Docker que Docker Desktop reciente
> rechaza; se fija `-Dapi.version` en Surefire (POM padre). Testcontainers subido a 1.20.4.

## 8. Bugs y retos durante el desarrollo
| ID | Síntoma | Causa raíz | Fix | ¿Lección? |
|---|---|---|---|---|
| — | (sin código todavía) | — | — | — |

## 9. Estándares y buenas prácticas aplicadas
Contract-first (ADR-05), MapStruct (ADR-06), auditoría (ADR-07), RFC 7807 (ADR-08),
Idempotency/ETag (ADR-09), probes (ADR-10), redacción + log de seguridad (ADR-11), gobernanza
Spectral (ADR-12), transactional outbox (ADR-04); logging estructurado (RNF-17); errores sin
fuga de internos (RNF-09); Flyway; inyección por constructor.

## 10. Cumplimiento y validación (definición de "done")
```
[ ] Todos los casos de prueba en ✅ PASS (casos_de_prueba.md — 43 casos).
[ ] Gatekeeper en verde (build + tests + lint) y cobertura ≥ 70 %.
[ ] Verificación por rol/condición ejecutada y documentada (ADM/OPE/AUD).
[ ] Gate de seguridad de endpoints verificado (escritura solo ADM; Auditor→403; redacción mgmtIp).
[ ] Contratos verificados con Pact (eventos asset.*) y gobernanza Spectral en verde.
[ ] Memoria global actualizada si hubo decisiones transversales.
```
