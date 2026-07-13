# Propuesta de módulo — asset-inventory-service

**Estado:** propuesta · **Fecha:** 2026-07-12 · **Fase:** A · **Lenguaje:** Java 21 / Spring Boot

> Planificación previa a codificar. Contrato de referencia: [`../openapi.yaml`](../openapi.yaml).
> Contexto: memoria técnica global y estándares de desarrollo (repo `management`).

## 1. Objetivo del módulo

Mantener el **inventario único de activos de red** — la **fuente de verdad** del sistema
(RF-01..05). Permite registrar, consultar, editar y dar de baja dispositivos, exponerlos por API
REST para que los demás microservicios los consulten, y publicar eventos de dominio ante cada
cambio. Sirve al **Administrador** (gestión completa) y a **Operador/Auditor** (solo consulta).

## 2. Alcance

- **Incluye:**
  - CRUD de dispositivos: registrar (RF-01), consultar/editar/dar de baja con *soft delete* (RF-02).
  - Listado con **búsqueda y filtrado** por hostname, IP de gestión, ubicación, criticidad,
    fabricante, modelo y estado, paginado (RF-04).
  - Exposición del inventario como API REST — fuente única de verdad (RF-03).
  - Publicación de eventos `asset.created` / `asset.updated` / `asset.decommissioned` (RF-05).
  - Columnas de auditoría (ADR-07), `/health`, RBAC por endpoint.
- **No incluye:** respaldos, auditoría de cumplimiento, escaneos, telemetría (otros servicios);
  gestión de usuarios/roles (la provee Keycloak); credenciales de acceso a los dispositivos
  (viven en `config-backup-service` / gestor de secretos, nunca aquí).
- **Dependencias:**
  - **PostgreSQL** (base de datos propia — *database-per-service*).
  - **RabbitMQ** (publicar eventos; con *transactional outbox*, ADR-04).
  - **Keycloak** (validación de JWT y roles).
  - POM padre del monorepo (Spring Boot, calidad, MapStruct).
  - **No consume** la API de ningún otro microservicio: es la fuente de verdad; los demás lo
    consumen a él.

## 3. Endpoints

| Unidad | Método / Ruta | Descripción | Rol(es) |
|---|---|---|---|
| Registrar dispositivo | `POST /api/v1/devices` | Alta de dispositivo | ADM |
| Listar / filtrar | `GET /api/v1/devices` | Búsqueda paginada con filtros | ADM, OPE, AUD |
| Consultar detalle | `GET /api/v1/devices/{deviceId}` | Detalle de un dispositivo | ADM, OPE, AUD |
| Editar (completo) | `PUT /api/v1/devices/{deviceId}` | Reemplazo completo | ADM |
| Editar (parcial) | `PATCH /api/v1/devices/{deviceId}` | Edición parcial | ADM |
| Dar de baja | `DELETE /api/v1/devices/{deviceId}` | *Soft delete* (status → BAJA) | ADM |
| Salud | `GET /health` | Estado del servicio | público |

## 4. Contratos con dependencias (verificados)

Este servicio **no consume** endpoints de otros servicios. Sus contratos salientes son:

**a) API propia** — definida en [`../openapi.yaml`](../openapi.yaml) (validada con redocly, 0 errores).

**b) Eventos que publica** — verificados contra
`management/documentos/arquitectura/especificaciones/comunicacion_por_eventos.md §4.1`:

| Evento (routing key) | Cuándo | Payload (campos exactos) |
|---|---|---|
| `asset.created` | Alta confirmada | `deviceId, hostname, mgmtIp, vendor, model, location, criticality, status` |
| `asset.updated` | Edición confirmada | idem + `changedFields: [..]` |
| `asset.decommissioned` | Baja lógica | `deviceId, hostname, status: "BAJA"` |

**c) Identidad (Keycloak):** JWT *Bearer*; claim de rol con valores `ADM` / `OPE` / `AUD`.

**DTOs relevantes** (nombres exactos del contrato):
```
Device               { id, hostname, mgmtIp, vendor, model, location, criticality,
                       status, createdAt, createdBy, updatedAt, updatedBy }
DeviceCreateRequest  { hostname*, mgmtIp*, vendor, model, location, criticality* }   (* requerido)
DeviceUpdateRequest  { hostname?, mgmtIp?, vendor?, model?, location?, criticality? } (parcial)
PageDevice           { content[], page, size, totalElements, totalPages, first, last }
ApiError             { code, message, traceId, timestamp }
Enums: Criticality {ALTA, MEDIA, BAJA} · DeviceStatus {ACTIVO, BAJA}
```

## 5. Reglas de negocio

- **RN1 — Unicidad:** `hostname` y `mgmtIp` son únicos en el inventario (entre los activos). Alta
  o edición que los duplique → **409** `DEVICE_ALREADY_EXISTS`.
- **RN2 — Criticidad válida:** `criticality ∈ {ALTA, MEDIA, BAJA}` → si no, **422**.
- **RN3 — Campos requeridos:** `hostname`, `mgmtIp`, `criticality` obligatorios en alta → **400/422**.
- **RN4 — IP de gestión válida:** `mgmtIp` con formato IPv4/IPv6 válido → si no, **422**.
- **RN5 — Soft delete:** dar de baja cambia `status` ACTIVO→BAJA (no borra físicamente). Un
  dispositivo en BAJA **no** es editable → **409** `DEVICE_DECOMMISSIONED`. Los listados excluyen
  los de baja salvo filtro explícito `estado=BAJA`.
- **RN6 — `status` protegido:** de solo lectura vía API; solo cambia por alta/baja, nunca por
  edición directa (campo protegido, estándares §11).
- **RN7 — Publicación de evento:** tras **confirmar** el cambio en BD, se publica el evento
  correspondiente vía *transactional outbox* (ADR-04): si la transacción no confirma, no hay evento.

## 6. Seguridad / RBAC del módulo

- **Escritura** (`POST`, `PUT`, `PATCH`, `DELETE /devices`) → **solo ADM**.
- **Lectura** (`GET /devices`, `GET /devices/{id}`) → **ADM, OPE, AUD**.
- **`/health`** → público (sin autenticación).
- **Gate de seguridad (obligatorio):** probar el acceso a los endpoints de escritura con el rol
  **menos** privilegiado (Auditor) → debe responder **403**; validado **en el servicio**, no
  asumido del Gateway.
- **Campos sensibles:** este servicio **no** almacena credenciales de dispositivos ni secretos.
  `createdBy`/`updatedBy` se exponen a roles autorizados. Nunca se expone la entidad JPA (solo DTOs).

## 7. Riesgos y decisiones de diseño

- **Contract-first (ADR-05):** las interfaces de API y los DTOs se **generan** desde `openapi.yaml`
  con `openapi-generator`; el controlador implementa la interfaz generada.
- **Mapeo entidad↔DTO (ADR-06):** **MapStruct**; nunca exponer la entidad JPA.
- **Auditoría (ADR-07):** `Device` es entidad **mutable** → cuarteto `created_at/by`,
  `updated_at/by` con JPA Auditing (`AuditorAware` desde el JWT).
- **Unicidad (RN1):** índice único en BD sobre `hostname` y `mgmtIp`; capturar
  `DataIntegrityViolationException` → **409** (no confiar solo en un chequeo previo, por carreras).
- **Soft delete (RN5):** columna `status`; filtro por defecto que excluye `BAJA` en los listados.
- **Concurrencia:** **bloqueo optimista** (`@Version`) en `Device` para ediciones concurrentes.
- **Consistencia evento↔BD:** *transactional outbox* (ADR-04).
- **Blast radius GLOBAL:** al ser la fuente de verdad, cambiar su contrato de API o el esquema de
  sus eventos obliga a re-probar los consumidores (config-backup, compliance-audit) con Pact.
- **Persistencia:** migraciones versionadas con **Flyway** (estándares §11).

## 8. Checklist de apertura (antes de codificar)

```
[x] Propuesta creada (este documento).
[ ] casos_de_prueba.md creado desde el TEMPLATE (categorías: SEC, RBAC, CRUD, VAL, FLOW, RN, ERR, CYBER).
[ ] memoria_tecnica.md del módulo iniciada.
[x] Contrato propio verificado (openapi.yaml, redocly 0 errores).
[x] Contratos salientes (eventos) verificados contra comunicacion_por_eventos.md §4.1.
[x] Gate de seguridad previsto para todos los endpoints (escritura solo ADM; probar Auditor→403).
```
