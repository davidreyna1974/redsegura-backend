# redSegura — backend (monorepo de microservicios)

> Monorepo de los 10 microservicios backend de redSegura (Java/Spring Boot + Python/FastAPI).

![estado](https://img.shields.io/badge/estado-en%20desarrollo-informational)
![fase](https://img.shields.io/badge/fase-Fundaci%C3%B3n-blue)
![java](https://img.shields.io/badge/Java-21-informational)
![python](https://img.shields.io/badge/Python-3.12-informational)
![licencia](https://img.shields.io/badge/licencia-por%20definir-lightgrey)

Parte del sistema **redSegura**. La documentación general (arquitectura, planificación, QA)
vive en el repositorio umbrella `management`:
[`../management/documentos/`](../management/documentos/README.md).

## 🧩 Microservicios

| Servicio | Fase | Lenguaje | RF | Responsabilidad |
|---|---|---|---|---|
| `asset-inventory-service` | A | Java | 01–05 | Inventario único de activos (fuente de verdad) |
| `config-backup-service` | A | Python | 06–10 | Respaldo de configuraciones (Netmiko/NAPALM), Git, drift |
| `compliance-audit-service` | A | Python | 11–14 | Auditoría de cumplimiento (Ansible) |
| `alerting-service` | A | Java | 29–31 | Evaluación de reglas y generación de alertas |
| `notification-service` | A | Java | 32–33 | Envío de notificaciones (email/Telegram) |
| `scan-orchestrator-service` | B | Python | 15–19 | Orquestación de escaneos (Nmap/OpenVAS) |
| `vulnerability-service` | B | Java | 20–24 | Hallazgos, enriquecimiento CVE/CVSS |
| `remediation-tracking-service` | B | Java | 25–28 | Flujo de remediación tipo ticket |
| `reporting-service` | B | Python | 34–35 | Reportes ejecutivos en PDF |
| `telemetry-collector-service` | B | Python | 36–37 | Telemetría SNMP |

> Estado actual: **Fase A en curso.** `asset-inventory-service` **implementado** (CRUD + búsqueda +
> RBAC + ETag/If-Match + idempotencia + redacción por rol + seguridad + observabilidad + eventos
> outbox + importación masiva; 57 tests, cobertura ≥ 70 %, **CI activo y gateando `main`**). Resto de
> servicios de Fase A sin iniciar. Contratos OpenAPI de Fase A definidos.

> **Probar un servicio localmente (vía Postman/curl):** entorno de desarrollo con Docker Compose
> (PostgreSQL + RabbitMQ + **Keycloak** sembrado + el servicio) y colección de Postman — ver
> [`deploy/README.md`](deploy/README.md). `docker compose -f docker-compose.dev.yml up --build`.

## 🛠️ Stack

- **Java 21 / Spring Boot 3.3.5** (servicios de lógica de negocio) — Maven, POM padre común.
- **Python 3.12 / FastAPI** (servicios de automatización de red y seguridad) — pip/uv.
- Persistencia **PostgreSQL** (una BD por servicio); mensajería **RabbitMQ**; auth **Keycloak**.
- Contratos **OpenAPI 3.1** por servicio (`<servicio>/openapi.yaml`).

## 📁 Estructura de un microservicio

```
<servicio>/
├── src/                 código fuente
├── documentos/          propuesta_modulo.md · casos_de_prueba.md · memoria_tecnica.md
├── Dockerfile
└── openapi.yaml         contrato de API (fuente de verdad)
```

## 🚀 Cómo construir y validar (gatekeeper por microservicio)

> **Prerrequisito (servicios Java): JDK 21.** El build fija el JDK a 21 con
> `maven-toolchains-plugin` (el target del proyecto y el de CI, Temurin 21), con independencia del
> JDK que lance a Maven. Copia [`toolchains.sample.xml`](toolchains.sample.xml) a `~/.m2/toolchains.xml`
> y ajusta la ruta a tu JDK 21 (macOS: `/usr/libexec/java_home -v 21`). Si falta, el build falla con
> un mensaje claro. En CI no hace falta: `actions/setup-java` genera el `toolchains.xml`.

**Servicios Java (Maven):**
```bash
mvn -pl <servicio> -am clean package                 # build
mvn -pl <servicio> test                              # tests
mvn -pl <servicio> checkstyle:check spotless:check   # lint/formato
mvn -pl <servicio> jacoco:report                     # cobertura (>= 70%)
```

**Servicios Python (FastAPI):**
```bash
ruff check <servicio>/ && mypy <servicio>/           # lint + tipos
pytest <servicio>/tests --cov=<servicio>             # tests + cobertura
```

**Validar un contrato OpenAPI:**
```bash
npx @redocly/cli lint <servicio>/openapi.yaml
```

## ✅ Calidad y QA

- Cobertura mínima **≥ 70 % statements** por servicio.
- Metodología: [Protocolo de 4 fases](../management/documentos/qa/protocolo_verificacion_4_fases.md).
- Convenciones y contexto para Claude Code: [`CLAUDE.md`](CLAUDE.md).

## 📚 Documentación

Índice del repo: [`documentos/README.md`](documentos/README.md). Documentación general del
sistema: [`../management/documentos/README.md`](../management/documentos/README.md).

## 🔐 Seguridad

Política de reporte en [`SECURITY.md`](SECURITY.md). Dependencias vigiladas con **Dependabot**
y escaneo en CI (SCA).

## 📄 Licencia

Por definir.

---
<sub>David Reyna Pineda · 2026</sub>
