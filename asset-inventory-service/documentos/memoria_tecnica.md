# Memoria técnica de módulo — asset-inventory-service

> Documento **vivo**: iniciado con el módulo (pre-código) y actualizado al cerrar cada fase.
> Memoria de "qué se hizo y por qué". Referencias: [`propuesta_modulo.md`](propuesta_modulo.md),
> [`casos_de_prueba.md`](casos_de_prueba.md), [`../openapi.yaml`](../openapi.yaml).

**Estado:** implementado y **✅ certificado (QA R1, 2026-07-14)** — 100 tests, cobertura ≥ 70 %, CI
activo · **Última actualización:** 2026-07-15

> **Verificación en vivo de endpoints (2026-07-15):** los 10 endpoints probados por HTTP real
> (curl/Postman) contra el entorno Docker Compose → 10/10 ✅. La pasada detectó y corrigió
> `HALLAZGO-LIVE-01`: `PUT /devices/{id}` no cumplía reemplazo completo (RFC 9110) — delegaba en la
> ruta de merge (PATCH) y no nulificaba los campos omitidos, por lo que no se podía conmutar un
> dispositivo dual-stack a solo-IPv6. Corregido con un flag `fullReplace` en `applyUpdate`
> (mantiene la invariante RF-05a: al menos una dirección → 422 si no) + 2 tests de regresión
> (`CRUD-04b`/`CRUD-04c`). Blast radius **local**, contrato sin cambios. Detalle:
> [`../../../management/documentos/qa/verificacion_endpoints_asset-inventory.md`](../../../management/documentos/qa/verificacion_endpoints_asset-inventory.md).

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
| **RFC 7807** / probes / redacción de direcciones | Propietario | Estándares de industria (ADR-08/10/11) |
| **Location embebida** `{site,room,row,rack,rackUnit}` | Recursos Site/Rack aparte | DCIM suficiente para MVP; recursos separados → backlog |
| **Flyway** para el esquema | Cambios manuales | Migraciones versionadas (estándares §11) |

## 3. Componentes / interfaces / capas
Patrón `controller → service → repository` (estándares §3.1):
- **`DeviceController`** — implementa la interfaz generada del `openapi.yaml`; filtro JWT + autorización por rol.
- **`DeviceService`** (`@Transactional`) — reglas de negocio (RN1..RN11), soft delete, unicidad, publicación de eventos.
- **`DeviceRepository`** (JPA) — persistencia; índices únicos en `serialNumber`, `hostname`, `managementIpv4`, `managementIpv6`.
- **`DeviceMapper`** (MapStruct) — entidad `Device` ↔ DTOs.
- **`Device`** (entidad) — extiende una `@MappedSuperclass Auditable`; `@Version` para concurrencia.
- **`OutboxWriter`** — escribe el evento `asset.*` (sobre completo) en `outbox_events` dentro de la transacción de negocio.
- **`OutboxRelay`/`OutboxRelayScheduler`** — publican los eventos pendientes al topic exchange `redsegura.events` (at-least-once).
- **`RabbitConfig`** — declara el exchange común durable (productor).
- **`MgmtIpRedactor`** — enmascara las direcciones de gestión (IPv4/IPv6) server-side para el Auditor (ADR-11/RF-05a).
- **`IpAddressNormalizer`** — valida familia y canonicaliza IPv6 (RFC 5952, Guava) para unicidad real (RF-05a).
- **`SecurityAuditLogger`** — log de seguridad (OWASP A09): denegaciones, auth fallida y mutaciones con actor.
- **`ProblemAuthenticationEntryPoint`/`ProblemAccessDeniedHandler`** — 401/403 en `problem+json` (ADR-08).
- **`IdempotencyRecord`/`IdempotencyRepository`** — persisten `(Idempotency-Key, usuario)` → hash del cuerpo + dispositivo creado.
- **`BulkImportService`/`BulkImportProcessor`** — alta de job + worker asíncrono (aislado por ítem) de importación masiva.
- **`JobService`/`JobMapper`** — ciclo de vida del job (estado + resultados) y mapeo al DTO del contrato.

## 4. Contratos con dependencias
- **API propia:** `../openapi.yaml` (validado; gobernado por Spectral).
- **Eventos publicados** (`asset.created` / `asset.updated` +`changedFields` / `asset.decommissioned`),
  **sobre `version` 1.1.0** (payload dual-stack). Formalizados como **JSON Schema** en la ubicación
  **compartida** del monorepo (`codigo/backend/contracts/events/asset-event.schema.json`) y verificados
  por `AssetEventContractIT` (conformidad productor-side, happy/edge/sad): cada evento emitido valida
  contra el schema, un payload malformado se rechaza (schema con dientes), y una operación fallida no
  emite evento. El Pact consumer-driven y la validación del **mismo** schema en el consumidor llegan
  con `config-backup`.
- **Aceptación (BDD/UAT):** escenarios Gherkin en español
  (`src/test/resources/features/inventario.feature`) automatizados con Cucumber (`AcceptanceTest`) —
  base de la UAT (ver `management/documentos/uat/`).
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
- **Redacción de direcciones de gestión (RN10/RF-05a):** para Auditor se enmascara la porción de host
  de IPv4/IPv6 (y su gateway) en el servidor **antes** de serializar (el valor real nunca viaja);
  ADM/OPE las ven completas.
- **Direccionamiento dual-stack (RF-05a):** el dispositivo lleva IPv4 y/o IPv6 (CIDR + gateway); IPv6
  canonicalizada (RFC 5952) para unicidad real.

## 6. Seguridad / RBAC del módulo
- **Escritura** (POST/PUT/PATCH/DELETE, bulk) → **ADM**. **Lectura** → ADM, OPE, AUD. Probes → público.
- **Redacción por rol:** direcciones de gestión (IPv4/IPv6) enmascaradas para Auditor (server-side, ADR-11/RF-05a).
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
vía Testcontainers + Flyway: auditoría, versión, unicidad de serial/hostname/managementIpv4/managementIpv6),
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

**Hito 4c-ter — auditoría de capacidad productiva (2026-07-13):** revisión completa del código ya
entregado contra el mandato "capacidad productiva real". Defectos corregidos:

1. **Filtros `fabricante`/`modelo` ignorados** — el controlador los recibía (están en el contrato)
   pero no los pasaba al servicio; filtrar por vendor/model no hacía nada. Ahora se propagan y se
   implementan como búsqueda parcial insensible a mayúsculas.
2. **Readiness falso** — `/health/readiness` siempre devolvía `UP`; ahora **verifica PostgreSQL**
   (`Connection.isValid`) y devuelve **503 `DOWN`** si la BD no responde (para que Kubernetes deje
   de enrutar tráfico).
3. **`size` de paginación sin tope** → acotado a `MAX_PAGE_SIZE=100` (evita DoS por página enorme).
4. **`sort` sin validar** (campo arbitrario → 500 con fuga interna) → whitelist de campos ordenables;
   campo no permitido → **400 `INVALID_REQUEST`** en `problem+json` (RNF-09).
5. **`LIKE` sin escapar** `%`/`_` en búsquedas parciales → se escapan los comodines del input.

`mvn verify` → **40 tests**, cobertura ≥70%, 0 Checkstyle.

**Hito 4d — redacción por rol + seguridad de datos (2026-07-13):** implementa RN10/ADR-11 y OWASP A09.

- **Redacción de `mgmtIp` server-side (`MgmtIpRedactor`):** para el rol Auditor se enmascara el
  último octeto (`10.0.0.11` → `10.0.0.***`) **antes** de serializar, en `GET /devices` y
  `GET /devices/{id}`; Administrador y Operador la ven en claro. El valor real **no viaja** en la
  respuesta (CYBER-02), no se limita a ocultarlo en el cliente.
- **401/403 en `application/problem+json`:** `ProblemAuthenticationEntryPoint` (401
  `UNAUTHENTICATED`) y `ProblemAccessDeniedHandler` (403 `ACCESS_DENIED`), registrados en la cadena
  de seguridad (global y en el resource server para fallos de JWT).
- **Log de auditoría de seguridad (`SecurityAuditLogger`, logger `SECURITY_AUDIT`):** registra
  denegaciones (403), autenticaciones fallidas (401) y mutaciones (alta/edición/baja) con su actor;
  **solo metadatos**, nunca `mgmtIp`/credenciales/secretos (RNF-17).

`mvn verify` → **44 tests** (RBAC-01/02/03, CYBER-02, SEC-01/02 en problem+json, SEC-06 auditoría),
cobertura ≥70%, 0 Checkstyle.

**Hito obs — observabilidad de 3 pilares (2026-07-13, transversal):** implementa A7/RNF-15/16/17.
Las dependencias van en el **POM padre** (heredadas por todos los servicios Java):

- **Métricas (RNF-15):** `micrometer-registry-prometheus` alimenta `/actuator/prometheus` (antes
  expuesto pero sin registro → métrica muerta). Tags con el nombre del servicio.
- **Trazas (RNF-16):** `micrometer-tracing-bridge-otel` genera y propaga `traceId`/`spanId` (W3C) y
  los inyecta en el MDC de los logs. El exportador OTLP a Jaeger se añade por entorno (sin colector,
  sin tráfico de red). Muestreo `TRACING_SAMPLE_PROBABILITY` (1.0 en dev).
- **Logs JSON (RNF-17):** `logstash-logback-encoder` + `logback-spring.xml` → cada línea es JSON con
  timestamp/nivel/logger/hilo/mensaje/MDC (incluye `traceId`) + `service`. Sin datos sensibles.

`mvn verify` → **46 tests** (OBS-01 scrape Prometheus, OBS-02 logs JSON con `traceId`). Nota: Spring
Boot **desactiva** métricas/tracing en tests por defecto; los tests usan `@AutoConfigureObservability`
(en producción están activos sin esa anotación).

**Hito 4e — eventos vía transactional outbox (2026-07-14):** implementa RN11/ADR-04/RNF-E4 y el
catálogo de eventos §3.3/§4.1.

- **Outbox (`outbox_events`, Flyway V3):** cada alta/edición/baja escribe el evento `asset.*` en la
  **misma transacción** que la mutación (`OutboxWriter`); no hay evento sin commit ni doble escritura
  BD↔broker. El `payload` es el sobre (envelope) completo ya serializado.
- **Sobre común (§3.3):** `eventId`, `eventType` (= routing key), `version` `1.0.0`, `occurredAt`,
  `traceId` (del MDC, RNF-16), `source`, `payload`. `asset.updated` añade `changedFields`.
- **Relay (`OutboxRelay` + `OutboxRelayScheduler`):** publica los pendientes al topic exchange
  `redsegura.events` (durable) con mensajes persistentes; semántica **at-least-once** (los consumidores
  deduplican por `eventId`). Desactivable con `redsegura.outbox.relay.enabled=false`.

`mvn verify` → **50 tests** (OutboxWriteIT: create/update/decommission escriben el evento correcto;
OutboxRelayIT: publicación real a RabbitMQ con Testcontainers), cobertura ≥70%, 0 Checkstyle.
*Refinamiento pendiente:* publisher confirms (marcar publicado solo tras ACK del broker).

**Hito 4f — importación masiva asíncrona (2026-07-14):** implementa RF-04 (`POST /devices/bulk`,
`GET /devices/bulk/jobs/{jobId}`).

- **Job + resultados (`import_jobs`/`import_job_results`, Flyway V4):** el POST valida (`minItems 1`,
  `maxItems 1000` del contrato → 422), crea el job `QUEUED` y **encola** el procesamiento; responde
  **202** con el job (+`Location` al recurso de estado). El GET devuelve estado y resultado por
  dispositivo (`CREATED`/`FAILED` + `detail`).
- **Worker asíncrono (`BulkImportProcessor` @Async, pool acotado):** procesa **aislado por ítem**
  (cada alta y cada registro de resultado en su propia transacción) → un duplicado falla solo ese
  dispositivo; los demás continúan. Cada alta exitosa emite `asset.created` (outbox).
- **Contexto de seguridad propagado** al hilo del worker (`DelegatingSecurityContextAsyncTaskExecutor`)
  → los dispositivos importados llevan el actor real en `created_by`.
- **Idempotencia a nivel job** (`BulkImportService`): misma `Idempotency-Key` + mismo cuerpo → mismo
  job (replay); cuerpo distinto → 409 (RN9), acotada por usuario.

`mvn verify` → **57 tests** (BulkImportIT: aislamiento de duplicados; BulkControllerIT: 202→COMPLETED
con polling, RBAC 403, 422 lote vacío, 404 job, replay idempotente), cobertura ≥70%, 0 Checkstyle.

**Hito RF-05a — direccionamiento dual-stack IPv4/IPv6 (2026-07-14, ADR-13):** reemplaza el único
`mgmtIp` (string IPv4) por `managementIpv4` y/o `managementIpv6`, cada uno con dirección +
`prefixLength` (CIDR) + `gateway` (estilo NetBox `primary_ip4`/`primary_ip6`).

- **Modelo:** embebido `ManagementAddress` (una clase, dos embebidos con overrides de columna);
  migración **V5** (columnas por familia, unicidad por dirección, CHECK "al menos una").
- **Validación/canonicalización (`IpAddressNormalizer`, Guava `InetAddresses`, sin DNS):** valida la
  familia y canonicaliza IPv6 (RFC 5952) antes de persistir → la **unicidad** es real (dos formas
  textuales de la misma IPv6 colisionan). Formato inválido / familia equivocada / ninguna dirección →
  422 `ADDRESS_INVALID`. "Al menos una" reforzado en servicio y por CHECK en BD.
- **Redacción por familia (`MgmtIpRedactor`):** enmascara la porción de host de ambas familias y de
  su gateway para el Auditor (IPv4 `10.0.0.***`, IPv6 `2001:db8:acad:1::***`).
- **Búsqueda:** el filtro `mgmtIp` coincide en IPv4 o IPv6; el término se canonicaliza para igualar
  la forma almacenada. **Eventos:** payload `asset.*` dual-stack (evento `version` 1.1.0).

`mvn verify` → **65 tests** (+8: IPv6 canónica, dual-stack, sin dirección→422, familia equivocada,
unicidad IPv6 por forma textual, redacción IPv6), cobertura ≥70%, 0 Checkstyle. Contrato regenerado.

**Backlog de producción (deuda explícita, hito propio):**
- **Seguridad JWT:** validar `issuer`/`audience` (hoy solo se valida la firma vía `jwk-set-uri`);
  wire de un `OAuth2TokenValidator` cuando se fije el realm de Keycloak.
- **Observabilidad — exportadores:** activar el exportador OTLP a Jaeger y el scrape de Prometheus
  por entorno (Docker Compose / k8s) cuando exista el stack; extraer `logback-spring.xml` a un módulo
  commons al scaffoldear el segundo servicio Java.
- **Mensajería:** publisher confirms (marcar publicado tras ACK); Pact del contrato de eventos `asset.*`.
- **Robustez BD (menor):** CHECK constraints de enums/`rack_unit`, índices en `loc_site`/`loc_rack`.
- **Rendimiento de búsqueda (menor):** índice GIN `pg_trgm` para los `LIKE %term%` (hoy la búsqueda
  insensible a acentos —`unaccent`, V6— es correcta pero sin índice para el comodín inicial).

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
> Además, el `mvn` de Homebrew arrastra openjdk 24, pero el proyecto compila a Java 21 y CI corre en
> Temurin 21; en Java 24 el Byte Buddy de Mockito 5.11 rompe los tests con `@MockBean`. Se fija el
> JDK del build a 21 con `maven-toolchains-plugin` (POM padre) + `~/.m2/toolchains.xml` → compiler y
> surefire usan Corretto 21 sin importar el JDK que lance Maven (ver `backend/toolchains.sample.xml`).

## 8. Bugs y retos durante el desarrollo
| ID | Síntoma | Causa raíz | Fix | ¿Lección? |
|---|---|---|---|---|
| B1 | Filtrar por `fabricante`/`modelo` no devolvía nada | El controlador recibía los parámetros pero no los pasaba al servicio | Propagar `vendor`/`model` a `search`/`DeviceSpecifications` | Sí: un parámetro del contrato sin cablear falla en silencio; test por filtro |
| B2 | Readiness `UP` con BD caída | El probe devolvía `UP` fijo, sin verificar dependencias | `Connection.isValid` → 503 `DOWN` si la BD no responde | Sí: readiness debe reflejar dependencias reales |
| B3 | `sort` con campo inválido → 500 con fuga interna | Campo pasado directo a Spring Data (`PropertyReferenceException`) | Whitelist de campos → 400 `INVALID_REQUEST` | Sí: validar entrada libre antes de la capa de datos (RNF-09) |
| B4 | `size` de página sin límite | Sin tope en `toPageable` | Acotar a `MAX_PAGE_SIZE=100` | Sí: paginación sin tope = vector de DoS |
| B5 | Búsqueda parcial: `%`/`_` del input actuaban como comodín | `LIKE` sin escapar | Escapar comodines + `ESCAPE` | Menor |
| E1 | `ddl-auto=validate` fallaba: `bpchar` vs `varchar` | Migración con `CHAR(64)` vs `String`→`VARCHAR` | Migración a `VARCHAR(64)` | Sí (ver memoria de entorno) |
| E2 | Tests con `@MockBean` fallaban: "Java 24 not supported by Byte Buddy" | El `mvn` de Homebrew usa openjdk 24; el proyecto/CI son Java 21 | `maven-toolchains-plugin` fija el build a JDK 21 (Corretto) — compilar/testear en el target, como CI | Sí (ver memoria de entorno) |
| E3 | El gate de formato/estilo no corría en `mvn verify` (violaciones acumuladas, detectadas al activar CI) | `spotless`/`checkstyle` estaban solo en `pluginManagement`, sin execution ligada a una fase | Ligar `spotless:check` + `checkstyle:check` a la fase `verify` en el POM padre → `mvn verify` es el gatekeeper único e insaltable | Sí: un gate no ligado a una fase no se ejecuta; el CI lo destapó |

## 9. Estándares y buenas prácticas aplicadas
Contract-first (ADR-05), MapStruct (ADR-06), auditoría (ADR-07), RFC 7807 (ADR-08),
Idempotency/ETag (ADR-09), probes (ADR-10), redacción + log de seguridad (ADR-11), gobernanza
Spectral (ADR-12), transactional outbox (ADR-04); logging estructurado (RNF-17); errores sin
fuga de internos (RNF-09); Flyway; inyección por constructor.

## 10. Cumplimiento y validación (definición de "done") — ✅ QA R1 certificada (2026-07-14)
```
[x] Todos los casos de prueba en ✅ PASS (casos_de_prueba.md — 70/73 PASS, 2 N/A, 1 diferido BSRCH-02).
[x] Gatekeeper en verde (build + tests + lint) y cobertura ≥ 70 % — 100 tests (98 + 2 regresión PUT).
[x] Verificación por rol/condición ejecutada y documentada (ADM/OPE/AUD).
[x] Gate de seguridad de endpoints verificado (escritura solo ADM; Auditor→403; redacción de direcciones).
[~] Gobernanza Spectral en verde. **Pact (eventos asset.*): pendiente** hasta que exista el primer consumidor.
[x] Memoria global actualizada (ADR-13, lecciones L03/L04/L-QA-01..03, reporte de QA).
```
> Reporte consolidado: [`../../../management/documentos/qa/reporte_qa.md`](../../../management/documentos/qa/reporte_qa.md).
