# Changelog — redSegura (backend)

Todos los cambios notables de este repositorio se documentan en este archivo.

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/);
versionado **por microservicio** según [SemVer](https://semver.org/lang/es/) (tags prefijados
por servicio, p. ej. `asset-inventory-service-v0.1.0`).

## [No publicado]

### `asset-inventory-service` — Añadido (implementación, **98 tests, QA R1.1 certificado**, cobertura ≥ 70 %)
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
- **POM padre** (`pom.xml`): Java 21, Spring Boot 3.3.5, `pluginManagement` de calidad
  (Spotless, Checkstyle, JaCoCo ≥ 70 %) y **MapStruct** (ADR-06).
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
