# Changelog — redSegura (backend)

Todos los cambios notables de este repositorio se documentan en este archivo.

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/);
versionado **por microservicio** según [SemVer](https://semver.org/lang/es/) (tags prefijados
por servicio, p. ej. `asset-inventory-service-v0.1.0`).

## [No publicado]
### Añadido
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
