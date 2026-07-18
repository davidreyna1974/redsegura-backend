# Matriz de trazabilidad de RNF — asset-inventory-service

Rastrea, RNF por RNF, cómo lo cumple este servicio y dónde está la evidencia. Es al RNF lo que
[`casos_de_prueba.md`](casos_de_prueba.md) es a lo funcional.

**Última actualización:** 2026-07-15 · **Fuente de RNF:**
[`proyecto_microservicios_redsegura.md §8`](../../../management/documentos/proyecto_microservicios_redsegura.md) ·
**Endurecimiento por etapas:** [`preparacion_produccion.md`](../../../management/documentos/arquitectura/preparacion_produccion.md)

**Leyenda:** ✅ cumplido · 🟡 en curso (este ciclo) · 🔵 diferido (obligatorio en su disparador) ·
⬜ N/A (justificado).

| RNF | Estado | Cómo se satisface | Evidencia | Disparador / motivo |
|---|---|---|---|---|
| RNF-01 rendimiento GET p95 < 300 ms | 🔵 | Sin prueba de carga aún | — | **PRE-REL** (prueba de carga) |
| RNF-02 respaldo < 30 s | ⬜ | — | — | Es de `config-backup-service` |
| RNF-03 auth OAuth2/OIDC (JWT) | ✅ | OAuth2 Resource Server; JWT de Keycloak | `SecurityConfig`, `application.yml` | — |
| RNF-04 RBAC e2e server-side | ✅ | `@PreAuthorize`/reglas por endpoint validadas en el servicio | `SecurityConfig`, `DeviceCertificationIT` (SEC-03/04) | — |
| RNF-05 TLS + segmentación interna | 🔵 | — | — | **DEPLOY** (gateway/infra) |
| RNF-06 secretos externalizados | 🟢/🔵 | Todo por variable de entorno (DB, JWKS, RabbitMQ) | `application.yml`, `docker-compose.dev.yml` | Gestor de secretos en **DEPLOY** |
| RNF-07 alcance de escaneo | ⬜ | — | — | Es de `scan-orchestrator-service` |
| RNF-08 SCA + escaneo de imagen (CI) | ✅ | Trivy `image` (inspecciona jars/deps + capas SO), bloqueante en crítico. En su estreno detectó 5 CVE críticos → subida a Spring Boot 3.5.16 | `.github/workflows/ci-asset-inventory-service.yml` | — |
| RNF-09 sin fuga de internos | ✅ | Manejo centralizado RFC 7807; sin stack traces | `GlobalExceptionHandler` | — |
| RNF-10 Resilience4j (timeouts/retry/CB) | ⬜→🔵 | No hace llamadas síncronas salientes hoy | — | **INT-SYNC** (obligatorio al introducir una) |
| RNF-11 degradación con gracia | ⬜→🔵 | Sin dependencia síncrona externa hoy | — | **INT-SYNC** |
| RNF-12 health probes | ✅ | liveness/readiness (readiness verifica Postgres) | `HealthController`, `HealthControllerTest` | — |
| RNF-13 stateless + HPA | 🟢/🔵 | Servicio stateless | (código) | HPA en **DEPLOY** |
| RNF-14 database-per-service | ✅ | BD propia; sin acceso a BD de otros | Flyway `db/migration`, `application.yml` | — |
| RNF-15 métricas Prometheus | ✅ | micrometer-registry-prometheus | `ObservabilityIT` (OBS-01) | — |
| RNF-16 trazas distribuidas | 🟢/🔵 | tracing-bridge-otel; traceId en logs | `ObservabilityIT`, `OutboxWriter` | Export por entorno (OTLP) en **DEPLOY** |
| RNF-17 logs estructurados sin PII | ✅ | JSON logs; redacción; sin secretos | `logback-spring.xml`, `MgmtIpRedactor` | — |
| RNF-18 cobertura ≥ 70 % | ✅ | jacoco:check ligado a `verify` | `pom.xml`, CI | — |
| RNF-19 gatekeeper en CI | ✅ | `mvn verify` único; status check requerido en `main` | `.github/workflows/ci-asset-inventory-service.yml` | — |
| RNF-20 documentación pre-código | ✅ | propuesta/casos/memoria + verificación en vivo | `documentos/` | — |
| RNF-21 contract testing (Pact) | ⬜→🔵 | Sin consumidor todavía; conformidad de eventos por JSON Schema | `AssetEventContractIT` | **INT-CONS** (primer consumidor) |
| RNF-22 paridad Compose ↔ k8s | 🔵 | 12-factor (config por entorno) | `application.yml` | **DEPLOY** |
| RNF-23 IaC (Terraform) | 🔵 | — | — | **DEPLOY** (infra) |
| RNF-24 presupuesto AWS | ⬜ | — | — | Nivel proyecto/infra |
| RNF-25/26 accesibilidad/UX dashboard | ⬜ | — | — | Repo `frontend` |
| RNF-27 OpenAPI + **Swagger UI runtime** | ✅ | springdoc sirve Swagger UI + `/v3/api-docs`; `/openapi.yaml` estático | `pom.xml` (springdoc), `application.yml`, `ApiDocsIT` | — |
| RNF-28 SemVer + CHANGELOG | ✅ | Tags por servicio; CHANGELOG Keep a Changelog | `CHANGELOG.md` | — |
| RNF-29 token issuer/audience | ✅ | JwtDecoder valida firma+iss+aud+exp | `SecurityConfig`, `AudienceValidator`, `AudienceValidatorTest`, `JwtIssuerAudienceValidationTest` (camino negativo iss/aud) | — |
| RNF-30 entrega garantizada de eventos | ✅ | Outbox + publisher confirms + relay `SKIP LOCKED` + DLQ (consumidor) | `OutboxRelay`, `OutboxRepository`, `OutboxRelayIT`, `OutboxRelayConfirmFailureTest` (camino negativo sin ACK) | — |
| RNF-31 readiness (esta matriz) | ✅ | Matriz + checklist mantenidas | este archivo + `preparacion_produccion.md` | — |

## Resumen

- **Etapa DEV — cerrados:** RNF-03/04/**08**/09/12/14/15/17/18/19/20/**27**/28/**29**/**30**. ✅
  (los 4 de endurecimiento —RNF-08/27/29/30— cerrados en el ciclo 2026-07-15.)
- **Diferidos con disparador (no opcionales):** RNF-01 (PRE-REL), RNF-05/13(HPA)/16(export)/22/23 y
  gestor de secretos (DEPLOY), RNF-10/11 (INT-SYNC), RNF-21 (INT-CONS). 🔵
- **N/A justificado:** RNF-02/07 (otros servicios), RNF-24 (infra), RNF-25/26 (frontend). ⬜

> **Estado del servicio:** **todos los RNF de etapa DEV cerrados** ✅ → cumple la Definición de "done"
> D6. Los 🔵 son obligatorios en su etapa (rastreados en `preparacion_produccion.md`); no bloquean el
> "done" funcional, sí la salida a producción cuando llegue su etapa.
