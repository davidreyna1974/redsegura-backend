# CLAUDE.md — redSegura (backend)

Guía para Claude Code al trabajar en este repositorio.

## Descripción del proyecto

**redSegura** es una plataforma de microservicios de automatización,
monitoreo y gestión de vulnerabilidades de red. Este repositorio
(`backend`) es un **monorepo** que contiene los 10 microservicios backend
del sistema (Java/Spring Boot + Python/FastAPI), cada uno en su propia
carpeta. La especificación completa del sistema (objetivos, alcance,
requerimientos funcionales/no funcionales, arquitectura) vive en
[`../management/documentos/proyecto_microservicios_redsegura.md`](../management/documentos/proyecto_microservicios_redsegura.md).

**Stack tecnológico:**
- Tipo de proyecto: backend de microservicios (monorepo poliglota)
- Lenguajes: Java 21 (Spring Boot 3.x) para servicios de lógica de negocio · Python 3.12 (FastAPI) para servicios de automatización de red/seguridad
- Persistencia: PostgreSQL — una base de datos independiente por microservicio (database-per-service)
- Build/gestor de paquetes: Maven (servicios Java) · pip/uv (servicios Python)
- Tests: JUnit 5 + Testcontainers (Java) · pytest + Testcontainers-python (Python)
- Lint/formato/tipos: Checkstyle + Spotless (Java) · ruff + mypy (Python)
- CI/CD: GitHub Actions, con activación por ruta (path-based triggers) — solo se construye/prueba el microservicio cuyo código cambió

**Integraciones / contratos externos:**
- API Gateway (Spring Cloud Gateway) enruta hacia estos servicios y centraliza autenticación/autorización.
- Service Discovery (Eureka/Consul) y Config Server (Spring Cloud Config) — infraestructura compartida, vive en el repo `management`.
- Message Broker (RabbitMQ) para comunicación asíncrona entre servicios.
- Autenticación: OAuth2/OIDC vía Keycloak; JWT propagado a cada microservicio.
- **Contratos (OpenAPI + catálogo de eventos): definidos (Fase A).** OpenAPI por servicio en
  `<servicio>/openapi.yaml`; catálogo de eventos en
  `../management/documentos/arquitectura/especificaciones/comunicacion_por_eventos.md`.

---

## 🧩 Microservicios de este repositorio

| Microservicio | Fase | Lenguaje | Carpeta |
|---|---|---|---|
| `asset-inventory-service` | A | Java (Spring Boot) | `asset-inventory-service/` |
| `config-backup-service` | A | Python (FastAPI) | `config-backup-service/` |
| `compliance-audit-service` | A | Python (FastAPI) | `compliance-audit-service/` |
| `alerting-service` | A | Java (Spring Boot) | `alerting-service/` |
| `notification-service` | A | Java (Spring Boot) | `notification-service/` |
| `scan-orchestrator-service` | B | Python (FastAPI) | `scan-orchestrator-service/` |
| `vulnerability-service` | B | Java (Spring Boot) | `vulnerability-service/` |
| `remediation-tracking-service` | B | Java (Spring Boot) | `remediation-tracking-service/` |
| `reporting-service` | B | Python (FastAPI) | `reporting-service/` |
| `telemetry-collector-service` | B | Python (FastAPI) | `telemetry-collector-service/` |

Detalle de responsabilidad de cada uno: `proyecto_microservicios_redsegura.md` §6.2 (descripción en lenguaje llano) y §6.3 (tabla técnica).

---

## ⚙️ Comandos del proyecto (gatekeeper) — por microservicio

El gatekeeper corre **por microservicio**, no de forma global sobre todo el
monorepo (correría build/test de 10 servicios en cada cambio, sin sentido).

**Servicios Java (Maven):** el gate completo es **`mvn -pl <servicio> -am clean verify`** — build +
tests (Testcontainers) + cobertura (jacoco:check ≥ 70%) + lint/formato (spotless + checkstyle) están
**ligados a la fase `verify`** en el POM padre, así que un solo comando lo verifica todo (y no se
puede saltar el lint). Requiere **JDK 21** (toolchain; ver `toolchains.sample.xml`). Desglose:
| Paso | Comando (opcional, desglosado) | Criterio de aprobación |
|---|---|---|
| Gate único | `mvn -pl <servicio> -am clean verify` | 0 errores/fallos, cobertura ≥ 70%, 0 lint |
| Build | `mvn -pl <servicio> -am clean package` | 0 errores |
| Tests | `mvn -pl <servicio> test` | 0 fallos |
| Lint | `mvn -pl <servicio> spotless:check checkstyle:check` | 0 errores |
| Cobertura | `mvn -pl <servicio> jacoco:report` | ≥ 70% statements |

**Servicios Python (FastAPI):**
| Paso | Comando | Criterio de aprobación |
|---|---|---|
| 1. Tipos/lint | `ruff check <servicio>/` && `mypy <servicio>/` | 0 errores |
| 2. Tests | `pytest <servicio>/tests --cov=<servicio>` | 0 fallos |
| Cobertura | (incluida en el comando anterior) | ≥ 70% statements |

> Este gatekeeper lo ejecuta el CI automáticamente por servicio, activado
> solo si cambiaron archivos dentro de `codigo/backend/<servicio>/**`
> (ver §CI/CD).

---

## ⚠️ Convenciones de Git — REGLAS CRÍTICAS

**NUNCA commitear directamente en `main` ni `develop`.** Todo trabajo va en
una rama `feature/`/`fix/`/`chore/` y se integra vía `git merge --no-ff`.

| Prefijo | Cuándo |
|---|---|
| `feature/<servicio>-<nombre>` | Funcionalidad nueva acotada a un microservicio |
| `fix/<servicio>-<nombre>` | Corrección acotada a un microservicio |
| `feature/<nombre>` / `chore/<nombre>` | Cambio transversal (config compartida, contratos, infraestructura del monorepo) |

Mensajes de commit: Conventional Commits (`tipo(scope): mensaje`), con
`scope` = nombre del microservicio cuando aplique.

---

## 🔁 CI/CD

**Un workflow por microservicio**, con trigger por ruta:
```yaml
on:
  push:
    paths: ['codigo/backend/<servicio>/**']
```
Cada workflow ejecuta el gatekeeper del servicio afectado (build + tests +
lint) en entorno limpio, con PostgreSQL/RabbitMQ como service containers si
el servicio los necesita para sus tests de integración.

**Contract testing (Pact):** workflow separado, verifica que ningún
servicio rompió un contrato consumido por otro antes de publicar una nueva
imagen.

**E2E:** vive en el repo `frontend` (Playwright contra el backend
desplegado), no en este repo.

---

## 🏷️ Versionado y releases

Al ser un **monorepo con microservicios de ritmos de evolución distintos**,
cada uno se versiona de forma **independiente** con SemVer, usando tags
prefijados por servicio:
```
asset-inventory-service-v0.1.0
config-backup-service-v0.1.0
```
Evita mezclar en un solo número de versión cambios de servicios que no
tienen relación entre sí. El CHANGELOG (`codigo/backend/CHANGELOG.md`)
agrupa las entradas por servicio.

---

## 🔐 Gobernanza de seguridad de dependencias (SCA)

- `SECURITY.md` en la raíz de este repo — canal de reporte privado.
- **Dependabot** (`.github/dependabot.yml`) cubriendo `maven`, `pip` y `github-actions`; agrupar minor/patch, majors individuales.
- Escaneo rápido en el gate de cada servicio (`mvn dependency-check` / `pip-audit`), bloqueante en `critical`. Escaneo pesado (OWASP dependency-check con base NVD) en workflow separado programado.
- Sin secretos en git — credenciales SSH de dispositivos, claves de API, credenciales de BD, siempre externalizadas.

---

## 📄 Documentación obligatoria por microservicio

Todo microservicio nuevo requiere, **antes de implementar**, en su propia carpeta:
```
codigo/backend/<servicio>/docs/
├── propuesta_modulo.md      ← planificación previa al código
├── casos_de_prueba.md       ← casos definidos ANTES de codificar
└── memoria_tecnica.md       ← documento vivo, actualizado por fase
```
Taxonomía completa de documentación del sistema (qué va global vs. por
repo vs. por servicio): `proyecto_microservicios_redsegura.md` §9.

---

## ⚠️ Protocolo de pruebas — Propuestas A–D (permanente, todos los microservicios)

**A — Casos de prueba por microservicio (pre-código).** Categorías
obligatorias: `SEC, RBAC/AUTHZ, CRUD, VAL, FLOW, RN, ERR, CYBER` (adapta a
`N/A` las que no apliquen; `UI/VIS/BSRCH` son del repo `frontend`, no de
este). Un microservicio no está "done" si hay casos sin `✅ PASS`.

**B — Verificación por unidad, no por microservicio completo.** Un
endpoint/comando no está terminado hasta que TODOS sus casos están en
`✅ PASS`.

**C — Gate de seguridad por cada endpoint nuevo:**
```
[ ] Tiene autorización explícita (roles permitidos) validada en el propio microservicio.
[ ] Se probó el acceso con el rol MENOS privilegiado sin acceso (Auditor / Solo lectura).
[ ] La autorización se valida en el backend, no se asume que el API Gateway ya filtró todo.
```

**D — Definición de "done" (no ofrecer continuar hasta cumplir las 4):**
```
[ ] 1. Todos los casos de prueba en ✅ PASS.
[ ] 2. Gatekeeper en verde (build + tests + lint) y cobertura ≥ 70%.
[ ] 3. Si el servicio expone o consume un contrato (API/evento), verificado con Pact.
[ ] 4. Documentación del microservicio (propuesta + casos + memoria técnica) actualizada.
```

---

## ⚠️ Protocolo pre-código — Consulta de contratos

**Antes de escribir cualquier cliente que consuma otro microservicio o un
evento del message broker, verifica el contrato real** — no asumir nombres
de campos ni estructura de payload. El catálogo de contratos (OpenAPI de
cada servicio + esquema de cada evento) vive en
`../management/documentos/arquitectura/memoria_tecnica_global.md` §4, con el detalle de
eventos en `../management/documentos/arquitectura/especificaciones/comunicacion_por_eventos.md`.

---

## ⚠️ Protocolo post-código — Cierre de unidad/módulo

```
[ ] Casos de prueba de ESTA unidad en ✅ PASS.
[ ] Gatekeeper en verde sobre la suite completa del servicio.
[ ] Verificación por rol: Administrador/Operador/Auditor, según aplique al endpoint.
[ ] Si el cambio modifica un contrato de API/evento compartido: tratado como
    blast radius GLOBAL — re-probar todos los microservicios consumidores de
    ese contrato, verificado con Pact (no solo el servicio local).
[ ] Datos sensibles (credenciales de dispositivos, secretos) ausentes en logs y respuestas para roles no autorizados.
```

---

## ⚠️ Protocolo de verificación en 4 fases (rondas de QA)

> **Regla inamovible:** una ronda de pruebas solo es válida si se ejecuta
> íntegra sobre una versión **congelada** del código. Si se corrige un bug a
> mitad, la ronda se invalida y se reinicia.

1) **Inventario** (código congelado, documentar bugs, NO corregir) →
2) **Corrección + gatekeeper** (documentar blast radius de cada fix) →
3) **Re-ejecución** completa desde cero →
4) **Certificación** (gatekeeper + cobertura + commit `chore(qa): ...`).

**Blast radius en este monorepo:** cambio local (lógica interna de un
microservicio) → re-probar solo ese servicio. Cambio en un **contrato de
API/evento compartido**, en el API Gateway, o en la configuración de
seguridad (Keycloak/JWT) → re-probar **todos** los microservicios
consumidores.

Reporte consolidado: `../management/documentos/qa/reporte_qa.md`.

---

## 🔐 Seguridad y RBAC

Roles del sistema: **Administrador, Operador, Auditor/Solo lectura**.
Matriz de acceso completa por módulo/acción:
`proyecto_microservicios_redsegura.md` §5.

- RBAC de extremo a extremo: se valida en el API Gateway **y** de forma
  independiente en cada microservicio — nunca confiar solo en que el
  Gateway ya filtró la solicitud.
- Autenticación: JWT (OAuth2/OIDC vía Keycloak); expiración y renovación
  gestionadas por el Gateway, propagadas a los servicios.
- El alcance autorizado de escaneo de red (rangos IP/CIDR) es configurable
  por entorno y se aplica como **control técnico obligatorio** en
  `scan-orchestrator-service` — ver RNF-07 del proyecto. Durante desarrollo
  y pruebas, ese alcance apunta exclusivamente a la red simulada
  (GNS3/Packet Tracer) — nunca a redes de terceros.

---

## 🧱 Estándares de código

Convenciones detalladas: `../management/documentos/arquitectura/estandares_desarrollo.md`.
Reglas mínimas no negociables:
- Nunca hardcodear URLs/secretos/credenciales de dispositivos — variables de entorno o gestor de secretos.
- Cada microservicio es dueño exclusivo de su base de datos — nunca acceder directamente a la BD de otro servicio.
- Inyección de dependencias por constructor (Java) / dependency injection nativa de FastAPI (Python).
- Excepciones tipadas para errores de negocio; manejo centralizado de errores por servicio; nunca filtrar stack traces al cliente.

---

## 🧪 Taxonomía de tests

| Tipo | Herramienta | Qué verifica |
|---|---|---|
| Unit | JUnit 5 (Java) / pytest (Python) | Lógica de unidades aisladas |
| Integración | Testcontainers (PostgreSQL/RabbitMQ reales) | Interacción con dependencias reales, sin mocks |
| Contract | Pact | Que un servicio no rompió el contrato que otro consume |
| E2E | Playwright (repo `frontend`) | Flujo completo de extremo a extremo, no vive en este repo |

Cobertura mínima: **70% statements** por microservicio.

---

## 📦 Estado actual

**Fase:** Fundación **completa**; Fase A **en curso**. `asset-inventory-service` (primer
microservicio) con toda la superficie funcional del contrato **implementada**; los demás de Fase A
sin iniciar.

**Fundación completada:** arquitectura global documentada (memoria técnica, diagrama,
estándares, eventos, protocolo de QA — en `management`, **ADR-01..13**); POM padre del monorepo
(Java 21, Spring Boot 3.3.5, calidad, MapStruct); **5 contratos OpenAPI de Fase A** validados y
**sintonizados con estándares de industria** (RFC 7807, health probes, Idempotency-Key,
ETag/If-Match) y **gobernados en CI con Spectral** (`.spectral.yaml`, ADR-12); documentación de
ambos repos; repos publicados en GitHub (`redsegura-backend`,
`redsegura-management`) con branch protection y esqueleto de CI.

**`asset-inventory-service` — implementado y ✅ QA R1 certificado (82 tests, cobertura ≥ 70 %, CI activo):** contract-first
con openapi-generator (ADR-05); CRUD + búsqueda/filtros; RBAC por rol validado en el servicio;
`ETag`/`If-Match` (ADR-09); idempotencia acotada por usuario + hash de cuerpo (RN9); **direccionamiento
de gestión dual-stack IPv4/IPv6** (RF-05a/ADR-13: `managementIpv4`/`managementIpv6` con CIDR + gateway,
canonicalización IPv6 RFC 5952); redacción de direcciones por rol server-side (ADR-11) + 401/403 en
`problem+json` + log de auditoría de seguridad (OWASP A09); observabilidad de 3 pilares (métricas
Prometheus, trazas, logs JSON — POM padre, RNF-15/16/17); eventos `asset.*` vía **transactional
outbox** a RabbitMQ (RN11/ADR-04); importación masiva asíncrona (RF-04). El gate único es `mvn verify`
(build + tests Testcontainers + cobertura + lint, todo ligado a la fase `verify`); el CI corre en
push/PR y es **status check requerido** en `main`.

**Próximos pasos (en orden):**
1. Resto de servicios de Fase A: `config-backup-service`, `compliance-audit-service`,
   `alerting-service`, `notification-service`.
2. Golden path de extremo a extremo en Docker Compose.
3. Pendiente de `asset-inventory-service`: Pact (al existir el primer consumidor); búsqueda
   insensible a acentos (BSRCH-02, `unaccent`) y demás deuda de producción registrada.

> **Deuda de producción registrada** (memoria técnica del módulo): validación `issuer`/`audience`
> del JWT; exportadores de observabilidad por entorno (OTLP→Jaeger, scrape Prometheus) + extraer
> `logback-spring.xml` a un commons; publisher confirms del outbox; CHECK constraints/índices menores;
> búsqueda insensible a acentos.

> **Mantenimiento:** ante cualquier cambio, seguir el Protocolo de 4 fases y
> actualizar la memoria técnica del microservicio afectado + la memoria
> técnica global si hubo una decisión transversal. Mantener este
> `CLAUDE.md` al día.
