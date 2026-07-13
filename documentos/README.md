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

## 🧩 Documentación por microservicio
Cada microservicio documenta, **antes** de codificar, en `<servicio>/documentos/`:
`propuesta_modulo.md` · `casos_de_prueba.md` · `memoria_tecnica.md`. (Se crean al construir
cada servicio; ninguno implementado todavía.)

## 🔗 Referencias del sistema
- [Memoria técnica global](../../management/documentos/arquitectura/memoria_tecnica_global.md)
- [Estándares de desarrollo](../../management/documentos/arquitectura/estandares_desarrollo.md)
- [Diagrama de arquitectura](../../management/documentos/arquitectura/diagrama_arquitectura.md)
- [Protocolo de verificación en 4 fases](../../management/documentos/qa/protocolo_verificacion_4_fases.md)
- [Plan general del proyecto](../../management/documentos/planificacion/plan_general_proyecto_redSegura.md)
- Contexto y convenciones para Claude Code: [`../CLAUDE.md`](../CLAUDE.md)
