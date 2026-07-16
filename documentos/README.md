# Documentación del repositorio — backend

Índice de la documentación **propia del repositorio backend**. La documentación **general del
sistema** (arquitectura, planificación, QA) vive en el repo umbrella `management`:
[`../../management/documentos/README.md`](../../management/documentos/README.md).

## 📐 Contratos de API (fuente de verdad por servicio)
| Servicio | Contrato |
|---|---|
| asset-inventory-service | [`../asset-inventory-service/openapi.yaml`](../asset-inventory-service/openapi.yaml) |
| config-backup-service | [`../config-backup-service/openapi.yaml`](../config-backup-service/openapi.yaml) |
| compliance-audit-service | [`../compliance-audit-service/openapi.yaml`](../compliance-audit-service/openapi.yaml) |
| alerting-service | [`../alerting-service/openapi.yaml`](../alerting-service/openapi.yaml) |
| notification-service | [`../notification-service/openapi.yaml`](../notification-service/openapi.yaml) |

> Catálogo de eventos (RabbitMQ): [`../../management/documentos/arquitectura/especificaciones/comunicacion_por_eventos.md`](../../management/documentos/arquitectura/especificaciones/comunicacion_por_eventos.md).

## 🧩 Documentación y artefactos por microservicio
Cada microservicio es **dueño** de su documentación y material de prueba, en su propia carpeta:
- `<servicio>/documentos/`: `propuesta_modulo.md` · `casos_de_prueba.md` · `memoria_tecnica.md` ·
  `verificacion_endpoints.md` (reporte de la verificación en vivo).
- `<servicio>/postman/`: colección Postman + `GUIA_PRUEBAS_POSTMAN.md`.

Implementado: **`asset-inventory-service`** ([documentos](../asset-inventory-service/documentos/) ·
[postman](../asset-inventory-service/postman/GUIA_PRUEBAS_POSTMAN.md)). El resto se crea al construir
cada servicio.

**Infra de desarrollo compartida:** `docker-compose.dev.yml` + [`deploy/`](../deploy/README.md)
(Keycloak sembrado). **Contratos de eventos compartidos:** [`contracts/events/`](../contracts/events/).
**UAT (validación con el cliente):** repo `management` →
[`documentos/uat/`](../../management/documentos/uat/README.md).

## 🔗 Referencias del sistema
- [Memoria técnica global](../../management/documentos/arquitectura/memoria_tecnica_global.md)
- [Estándares de desarrollo](../../management/documentos/arquitectura/estandares_desarrollo.md)
- [Diagrama de arquitectura](../../management/documentos/arquitectura/diagrama_arquitectura.md)
- [Protocolo de verificación en 4 fases](../../management/documentos/qa/protocolo_verificacion_4_fases.md)
- [Plan general del proyecto](../../management/documentos/planificacion/plan_general_proyecto_redSegura.md)
- Contexto y convenciones para Claude Code: [`../CLAUDE.md`](../CLAUDE.md)
