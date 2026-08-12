# Changelog — redSegura (backend)

Todos los cambios notables de este repositorio se documentan en este archivo.

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/);
versionado **por microservicio** según [SemVer](https://semver.org/lang/es/) (tags prefijados
por servicio, p. ej. `asset-inventory-service-v0.1.0`).

## [No publicado]

### Validación de fidelidad con dispositivos de red (SSH multi-vendor) — Fase 0
- **Plan maestro** `documentos/validacion_dispositivos/plan_validacion_dispositivos.md`: escalera de
  3 niveles (fixtures → NOS emulado → hardware), matriz multi-vendor, criterios de éxito y gates.
- **Guía de captura de fixtures** (`guia_captura_fixtures.md`): qué capturar, redacción de secretos
  (RNF-06/17) y convención de nombres.
- **Guía de captura sobre Cisco DevNet** (`guia_captura_devnet.md`): fuente Cisco real y gratuita
  (IOS-XE/NX-OS) — acceso (cuenta Cisco.com/CCO, no NetAcad), always-on vs reserved, credenciales
  dinámicas, comandos de captura.
- **Checkpoint de pausa prolongada:** CLAUDE.md apunta al handoff de sesión
  (`management/documentos/sesiones/contexto_sesion_siguiente.md`) como punto de retomada.

### `config-backup-service` — ✅ QA certificado (4 fases): correcciones de Fase 2
- **`HALLAZGO-QA-CBS-01` (filtros de listado):** `GET /backups` ahora honra todos los parámetros del
  contrato —`hostname`/`mgmtIp` (resueltos contra la vista de dispositivos), `status`, `from`/`to`
  (rango sobre `capturedAt`) y `sort`—; antes ignoraba silenciosamente los no implementados.
- **`HALLAZGO-QA-CBS-02` (idempotencia de escritura, RN-CB7):** cabecera `Idempotency-Key` honrada en
  `POST /devices/{id}/backups`, `POST /backups` y `POST /schedules` (nuevo `app/idempotency.py` + tabla
  `idempotency_keys`, migración 0004). Reserva la clave por actor antes del efecto; reintento con
  misma clave+cuerpo → replay sin duplicar; distinto cuerpo → **409**; fallo de negocio libera la
  clave. Contrato: +respuesta `409` en los 3 POST. **+17 tests** (76 → **93**, cobertura 95 %).
- Ronda de certificación bajo el Protocolo de 4 fases (código congelado, inventario contra el
  contrato, re-ejecución + verificación en vivo). Reporte `documentos/reporte_certificacion_qa.md`.
  Lección de proceso **L-QA-08**.

### `config-backup-service` — Implementación completa (Python/FastAPI, **76 tests**, cobertura 95 %, CI verde)
- **Respaldo y versionado (RF-06/07/10):** captura running/startup vía SSH tras interfaz
  `DeviceConnector` (Netmiko real + doble de test); versionado en repo Git interno (GitPython) + diff;
  `unsavedChanges` (running≠startup, ADR-02); metadatos en Postgres; produce `config.*` vía outbox.
- **Alcance de conexión (RNF-07):** control técnico de CIDRs autorizados (`assert_in_scope`) — rechaza
  fuera de alcance **antes** de conectar (dev/test = red simulada).
- **Resiliencia (RNF-10):** reintentos con backoff en SSH (`with_retries`); **hilos de fondo
  auto-recuperables** (`run_resilient`: consumidor/relay/scheduler/retención reconectan con backoff
  ante caídas del broker/BD) — cierra `HALLAZGO-LIVE-CBS-01`.
- **Drift (RF-09):** compara running en vivo vs último respaldo; emite `config.drift_detected`.
- **Jobs por lotes (RF-08):** `POST /backups` y `/drift-checks` asíncronos → `jobId`; dispatcher
  inyectable; tablas `jobs`/`job_results`; objetivo por `scope`/`deviceIds`/`filter` (exactamente uno).
- **Programaciones cron (RF-08):** `POST/GET /schedules` (crear solo ADM); `croniter` + scheduler de
  fondo con `SKIP LOCKED`; `next_run_at`; disparo como `scheduler:<id>`.
- **Eventos (RNF-21/30):** consumidor idempotente de `asset.*` (dedupe por `eventId`) que mantiene la
  vista local; **Pact consumidor** (INT-CONS); transactional outbox + relay `SKIP LOCKED` + publisher
  confirms + DLQ sobre RabbitMQ real (Testcontainers).
- **Seguridad (RNF-04/06/29):** JWT en profundidad (firma JWKS + `issuer` + `audience` + expiración);
  RBAC por endpoint validado en el servicio; credenciales SSH externalizadas (nunca en código/BD/logs).
- **Observabilidad 3 pilares (RNF-15/16/17):** `/metrics` Prometheus (HTTP + dominio: respaldos, drift,
  eventos publicados, jobs); trazas OpenTelemetry (traceId en logs); logs JSON con **redacción de la
  contraseña SSH**.
- **Endurecimiento:** Dockerfile multi-stage **no-root** + HEALTHCHECK; **graceful shutdown** (uvicorn);
  **retención** de datos operativos (`purge_old`, nunca respaldos/Git); **SCA** en CI (pip-audit +
  Trivy imagen, bloqueante en crítico).
- **Verificación en vivo:** 14/14 operaciones OpenAPI sobre `docker-compose.dev.yml` con JWT reales de
  Keycloak (reporte `documentos/verificacion_endpoints.md` + colección Postman + guía). Detectó y
  corrigió `HALLAZGO-LIVE-CBS-01` (relay sin reconexión), re-verificado reiniciando RabbitMQ.
- **Entorno dev:** `docker-compose.dev.yml` extendido (2.ª BD `config_backup`, servicio Python en
  `:8082`, mensajería activa).

### `asset-inventory-service` — Endurecimiento a producción (2.º ciclo: contenedor, apagado, datos, métricas)
- **Contenedor no-root:** el `Dockerfile` crea y usa un usuario sin privilegios (`USER appuser`).
- **Graceful shutdown:** `server.shutdown=graceful` + `timeout-per-shutdown-phase` → drena peticiones
  en vuelo y tareas `@Async` del bulk ante `SIGTERM` (rolling deploy de k8s).
- **Retención de datos operativos:** purga programada (`RetentionCleanup`, desactivable) de
  `outbox_events` publicados / `idempotency_keys` / `import_jobs` antiguos —evita crecimiento sin
  límite—; nunca toca eventos pendientes; ventanas configurables. Test `RetentionCleanupIT`.
- **Métricas de dominio (RNF-15):** contadores `redsegura.devices.created` y
  `redsegura.outbox.events.published`. Test `ObservabilityIT` (OBS-03).
- Deuda diferida registrada con disparador (DAST/PRE-REL, runbook/DEPLOY, dashboards+SLO/DEPLOY,
  mutation testing, LICENSE) en `preparacion_produccion.md`.

### `asset-inventory-service` — Endurecimiento a producción (RNF-08/27/29/30 · ADR-14..17)
- **JWT en profundidad (RNF-29/ADR-14):** el `JwtDecoder` valida ahora `issuer` + `audience` +
  expiración además de la firma; un token de otro realm/audiencia → `401`. `AudienceValidator` +
  mapper de audiencia (`redsegura-backend`) en el realm. Config `KEYCLOAK_ISSUER`/`KEYCLOAK_AUDIENCE`.
- **Entrega garantizada de eventos (RNF-30/ADR-15):** el relay del outbox toma el lote con
  `FOR UPDATE SKIP LOCKED` (seguro con múltiples réplicas) y marca publicado **solo tras el ACK**
  del broker (publisher confirms `correlated`); si no confirma, revierte y reintenta.
- **Cadena de suministro en CI (RNF-08/ADR-16):** Trivy `fs` (dependencias) + Trivy `image`
  (imagen del contenedor), **bloqueantes en severidad crítica**, en el gate del servicio.
- **OpenAPI navegable en runtime (RNF-27/ADR-17):** springdoc sirve **Swagger UI** + `/v3/api-docs`;
  el `openapi.yaml` (fuente de verdad) se publica como recurso estático en `/openapi.yaml`.
- Trazado por servicio en `documentos/matriz_rnf.md`; diferidos en `preparacion_produccion.md`.

### Transversal (POM padre) — Seguridad
- **Spring Boot 3.3.5 → 3.5.16** para corregir **5 CVE CRÍTICOS** que el nuevo gate de SCA (Trivy,
  RNF-08) detectó en dependencias transitivas: Tomcat `tomcat-embed-core` 10.1.31 (CVE-2025-24813
  RCE, CVE-2026-43512/43515 bypass de auth → 10.1.55) y `spring-security-web` 6.3.4 (CVE-2026-22732
  → 6.5.11). Ajustes: springdoc 2.6.0 → 2.8.17; `@MockBean` → `@MockitoBean`.

### `asset-inventory-service` — Corregido
- **`PUT /devices/{id}` ahora es reemplazo completo (RFC 9110)** — `HALLAZGO-LIVE-01`. Antes delegaba
  en la ruta de merge de PATCH y no nulificaba los campos omitidos, impidiendo conmutar un dispositivo
  dual-stack a solo-IPv6. Se separa la semántica con un flag `fullReplace` en `DeviceService.applyUpdate`
  (PUT nulifica opcionales omitidos y mantiene la invariante RF-05a → 422 si quedaría sin dirección; PATCH
  conserva merge). +2 tests de regresión (`CRUD-04b`/`CRUD-04c`). Detectado por la **verificación en vivo
  de los 10 endpoints** (curl/Postman sobre Docker Compose, 10/10 ✅).

### `asset-inventory-service` — Añadido (implementación, **112 tests, QA certificado**, cobertura ≥ 70 %)
- **Scaffolding contract-first** (ADR-05): openapi-generator produce las interfaces de API
  (`DevicesApi`/`BulkApi`/`HealthApi`) y los DTOs; los controladores **implementan** la interfaz
  → el código cumple el contrato por construcción.
- **CRUD de dispositivos** + búsqueda/filtros (hostname/vendor/model parciales insensibles a
  mayúsculas; site/rack/tipo/criticidad/estado), paginación con tope y `sort` con whitelist.
- **Persistencia:** JPA + Flyway (V1 devices, V2 idempotencia, V3 outbox, V4 jobs); auditoría
  `created/updated_by/at` (ADR-07); unicidad por índice; baja lógica (RN6).
- **Seguridad:** OAuth2 Resource Server (JWT Keycloak), RBAC por endpoint validado en el servicio;
  `ETag`/`If-Match` → bloqueo optimista (ADR-09); idempotencia acotada por usuario + hash de cuerpo
  (RN9); redacción de `mgmtIp` por rol server-side (ADR-11); 401/403 en `problem+json`; log de
  auditoría de seguridad (OWASP A09). Errores RFC 7807 (ADR-08).
- **Eventos `asset.created/updated/decommissioned`** vía **transactional outbox** a RabbitMQ
  (RN11/ADR-04): escritura en la misma transacción + relay at-least-once con mensajes persistentes.
- **Importación masiva asíncrona** (RF-04): `POST /devices/bulk` (202 + job) y
  `GET /devices/bulk/jobs/{jobId}`; worker `@Async` aislado por ítem con resultado por dispositivo.
- **Health probes** liveness/readiness (readiness verifica PostgreSQL, ADR-10).
- **Direccionamiento de gestión dual-stack IPv4/IPv6** (RF-05a/ADR-13, Flyway V5): `managementIpv4`
  y/o `managementIpv6` (dirección + prefijo CIDR + gateway, estilo NetBox); canonicalización IPv6
  (RFC 5952) para unicidad real; validación por familia (422); redacción por familia; búsqueda por
  ambas familias.
- **Búsqueda insensible a acentos** (`unaccent`, Flyway V6) además de mayúsculas.
- **Conformidad de contrato de eventos:** JSON Schema compartido (`contracts/events/asset-event.schema.json`)
  verificado por `AssetEventContractIT` (happy/edge/sad); sobre de evento `version` 1.1.0.
- **Pruebas de aceptación BDD** (Cucumber, Gherkin en español) como base de la **UAT**.
- **Certificación QA de 4 fases** (R1 + re-certificación R1.1); reporte consolidado en `management`.
- **Entorno de desarrollo local** — infra compartida (`docker-compose.dev.yml` + `deploy/keycloak/`):
  PostgreSQL + RabbitMQ + **Keycloak sembrado** (realm `redsegura`, usuarios ADM/OPE/AUD) + el servicio
  empaquetado, para probarlo vía Postman/curl con JWT reales. **Colección Postman por servicio** en
  `asset-inventory-service/postman/` con ejemplos de request y respuestas esperadas, y **guía paso a
  paso** (`GUIA_PRUEBAS_POSTMAN.md`).
- **Verificación en vivo de los 10 endpoints** sobre ese entorno (10/10 ✅); reporte en
  `asset-inventory-service/documentos/verificacion_endpoints.md`.

### Transversal (POM padre / infraestructura) — Añadido
- **Observabilidad de 3 pilares** heredada por todos los servicios Java (RNF-15/16/17):
  `micrometer-registry-prometheus` (métricas), `micrometer-tracing-bridge-otel` (traceId/spanId) y
  `logstash-logback-encoder` + `logback-spring.xml` (logs JSON).
- **Build reproducible en JDK 21** con `maven-toolchains-plugin` (`toolchains.sample.xml`).
- **CI de `asset-inventory-service` activo** (push/PR con filtro por rutas, `mvn verify` único) y
  **status check requerido** en `main`; actions en `@v5`.

### Cambiado
- **Gate de calidad ligado a `verify`:** `spotless:check` + `checkstyle:check` se ejecutan en la
  fase `verify` del POM padre → `mvn verify` es el gatekeeper único e insaltable (antes el lint
  vivía solo en `pluginManagement` y podía saltarse).
- **`asset-inventory` — direccionamiento de gestión dual-stack (RF-05a/ADR-13):** `mgmtIp` (string
  IPv4) → `managementIpv4` y/o `managementIpv6`, cada uno con `address` + `prefixLength` (CIDR) +
  `gateway` (estilo NetBox `primary_ip4`/`primary_ip6`). Al menos una obligatoria; IPv6 canonicalizada
  (RFC 5952, Guava) para unicidad real; validación por familia server-side (422 `ADDRESS_INVALID`);
  redacción por rol de ambas familias; filtro de búsqueda por IPv4 o IPv6; payload de eventos `asset.*`
  dual-stack (evento `version` 1.1.0). Migración V5.
- **`asset-inventory` — `size` de página fuera de rango:** de **500** (fuga, `ConstraintViolationException`
  sin manejar) a **422** `problem+json`; contrato alineado (`size` máx 100). Hallado en la QA.

### Añadido (fundación)
- Inicialización del monorepo backend con `CLAUDE.md`, `.gitignore` poliglota y git-hook
  `pre-commit` (bloquea commits directos a `main`/`develop`).
- **POM padre** (`pom.xml`): Java 21, Spring Boot 3.3.5 (luego actualizado a **3.5.16** por seguridad,
  ver arriba), `pluginManagement` de calidad (Spotless, Checkstyle, JaCoCo ≥ 70 %) y **MapStruct** (ADR-06).
- **Contratos OpenAPI 3.1 de Fase A** (5 servicios): `asset-inventory-service`,
  `config-backup-service`, `compliance-audit-service`, `alerting-service`,
  `notification-service`. Incluyen modelo de error uniforme, paginación, filtros, `/health`,
  RBAC por operación (`x-roles`) y columnas de auditoría (ADR-07).
- Documentación del repositorio: README, SECURITY, plantilla de PR, Dependabot, esqueleto de
  CI (workflows en `workflow_dispatch` hasta scaffolding) e índice de documentación.
- Repositorio publicado en GitHub (`redsegura-backend`, público) con branch protection.
- `asset-inventory-service`: propuesta de módulo (documentación pre-código).
- **Gobernanza de contratos (ADR-12):** `.spectral.yaml` que codifica los estándares OpenAPI
  (RFC 7807, health probes, security global, sin `ApiError`) + workflow de CI que lo verifica
  sobre todos los `openapi.yaml` en cada push/PR. Aplica a Fase A y Fase B.
- Estándares de industria en los contratos de Fase A: RFC 7807 Problem Details, health probes
  liveness/readiness, `Idempotency-Key` y `ETag`/`If-Match` (ADR-08..11). `asset-inventory`
  además: identidad estable (`serialNumber`), `deviceType`, ubicación DCIM, bulk import,
  redacción de `mgmtIp` por rol.
