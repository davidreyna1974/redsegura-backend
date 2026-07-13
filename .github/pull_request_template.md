<!-- Plantilla de Pull Request — redSegura (backend) -->

## Descripción
<!-- ¿Qué cambia y por qué? Indica el microservicio afectado (scope). -->

## Tipo de cambio
- [ ] `feature/` — nueva funcionalidad o módulo
- [ ] `fix/` — corrección
- [ ] `chore/` — infraestructura, configuración o documentación

## Microservicio(s) afectado(s)
<!-- p. ej. asset-inventory-service -->

## Checklist (Protocolo del repositorio)
- [ ] La rama parte de `develop` y se integra vía `merge --no-ff` (nunca commit directo a `main`/`develop`).
- [ ] **Gatekeeper en verde** para el/los servicio(s) afectado(s):
  - Java: `mvn -pl <servicio> -am clean package` · `mvn -pl <servicio> test` · `mvn -pl <servicio> checkstyle:check spotless:check`
  - Python: `ruff check <servicio>/ && mypy <servicio>/` · `pytest <servicio>/tests --cov=<servicio>`
- [ ] Cobertura ≥ 70 % statements en el/los servicio(s).
- [ ] Casos de prueba de la unidad en ✅ PASS (categorías: SEC, RBAC, CRUD, VAL, FLOW, RN, ERR, CYBER).
- [ ] Endpoints nuevos con **autorización explícita por rol** validada en el propio servicio (gate de seguridad).
- [ ] Probado el acceso con el rol menos privilegiado (Auditor) que **no** debe tener acceso.
- [ ] Datos sensibles (credenciales de dispositivos) ausentes en respuestas y logs para roles no autorizados.
- [ ] Si cambia un contrato de API/evento compartido: **blast radius global** → re-probados los consumidores (Pact).
- [ ] Documentación del módulo (propuesta + casos + memoria técnica) y CHANGELOG actualizados.
- [ ] Sin secretos en el diff; sin datos de prueba residuales.

## Evidencia de pruebas
<!-- Pega el resumen real de build/tests/cobertura -->
