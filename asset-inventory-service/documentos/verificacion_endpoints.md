# Verificación en vivo de endpoints — `asset-inventory-service`

Registro de la **prueba manual/en vivo** de los **10 endpoints** del contrato, ejecutada por
HTTP real (curl / colección Postman) contra el **entorno de desarrollo** (Docker Compose:
PostgreSQL + RabbitMQ + Keycloak sembrado + el servicio). Complementa la certificación
automatizada (100 tests, gatekeeper `mvn verify`) con una pasada de humo end-to-end sobre el
servicio empaquetado y desplegado.

- **Fecha:** 2026-07-15
- **Entorno:** `docker-compose.dev.yml` (servicio en `localhost:8081`, Keycloak en `localhost:8080`)
- **Autenticación:** JWT reales de Keycloak (realm `redsegura`), roles ADM/OPE/AUD
- **Guía para reproducir:** [`../postman/GUIA_PRUEBAS_POSTMAN.md`](../postman/GUIA_PRUEBAS_POSTMAN.md)

---

## Resultado: 10/10 endpoints ✅

| # | Método + endpoint | Roles | Prueba | Resultado | Estado |
|---|---|---|---|---|---|
| 1 | `GET /health/liveness` | público | probe de vida | `200` `{"status":"UP"}` | ✅ |
| 2 | `GET /health/readiness` | público | probe de disponibilidad | `200` `{"status":"UP"}` | ✅ |
| 3 | `GET /devices` | ADM/OPE/AUD | listar/buscar por `hostname` | `200` + página (`totalElements`, `content[]`) | ✅ |
| 4 | `POST /devices` | ADM | alta IPv4 con datos completos | `201` + `ETag: "0"` + `Location` | ✅ |
| 5 | `GET /devices/{id}` | ADM/OPE/AUD | consulta por id | `200` + `ETag` | ✅ |
| 6 | `PUT /devices/{id}` | ADM | **reemplazo completo** con `If-Match` | `200` + `ETag` bump; campos omitidos → `null` | ✅ (ver hallazgo) |
| 7 | `PATCH /devices/{id}` | ADM | edición parcial (merge-patch) con `If-Match` | `200` + `ETag` bump; solo cambia lo enviado | ✅ |
| 8 | `DELETE /devices/{id}` | ADM | baja lógica con `If-Match` | `204` | ✅ |
| 9 | `POST /devices/bulk` | ADM | importación masiva asíncrona | `202` + `jobId`, `status: QUEUED` | ✅ |
| 10 | `GET /devices/bulk/jobs/{jobId}` | ADM/OPE/AUD | estado del job | `200` `status: COMPLETED`, `succeeded: 2/2` | ✅ |

### Endurecimiento a producción (re-verificado en vivo, 2026-07-17, sobre Spring Boot 3.5.16)

| Caso | Resultado | Estado |
|---|---|---|
| RNF-29 — sin token en endpoint protegido | `401` | ✅ |
| RNF-29 — token válido (issuer+audience OK) opera normal | `201`/`200` end-to-end | ✅ |
| RNF-27 — `/openapi.yaml` (contrato estático) sin token | `200` (estable, 5/5) | ✅ |
| RNF-27 — `/v3/api-docs` y `/swagger-ui/index.html` | `200` | ✅ |
| RBAC — Operador (solo lectura) intenta escribir | `403` | ✅ |
| Redacción de IP para Auditor | `10.x.x.***` | ✅ |

> Nota: en arranque en frío, las rutas de doc pueden dar un `401` fugaz mientras springdoc termina de
> inicializarse (tras `readiness=UP`); se estabilizan en `200` en segundos. No es un defecto funcional.

### Dimensiones transversales de seguridad (verificadas en vivo)

| Caso | Resultado | Estado |
|---|---|---|
| Sin token en `/api/v1/devices/**` | `401` `problem+json` | ✅ |
| Operador (OPE, solo lectura) intenta `POST`/`DELETE` | `403` `ACCESS_DENIED` | ✅ |
| Auditor (AUD) consulta un dispositivo | `mgmtIp` **enmascarada** (`10.0.0.***`) | ✅ |
| ETag / `If-Match` (bloqueo optimista) | `ETag` incrementa en cada escritura; edición/baja lo exigen | ✅ |
| Canonicalización IPv6 (RFC 5952) | `2001:0DB8:ACAD:1::11` → `2001:db8:acad:1::11` | ✅ |

---

## Hallazgo de la prueba: `HALLAZGO-LIVE-01` (corregido)

La prueba en vivo detectó un defecto que la suite automatizada **no** cubría:

- **Endpoint:** `PUT /devices/{id}` (reemplazo completo).
- **Síntoma:** al enviar un PUT con **solo** `managementIpv6` (omitiendo `managementIpv4`,
  `vendor`, `model`), esos campos omitidos **conservaban su valor anterior** en vez de quedar
  en `null`.
- **Causa raíz:** `DeviceService.replace()` (PUT) delegaba en el mismo método
  `applyUpdate()` que `update()` (PATCH), que **solo aplica campos no nulos** — semántica
  correcta para PATCH (JSON Merge Patch, RFC 7386) pero **incorrecta para PUT**, que según el
  contrato (`DeviceUpdateFull` = "edición completa") y **RFC 9110 §9.3.4** debe reemplazar el
  recurso por entero.
- **Impacto productivo:** un cliente no podía, vía PUT, quitar un campo ni **conmutar un
  dispositivo de dual-stack (IPv4+IPv6) a solo-IPv6** — el IPv4 quedaba pegado. Rompía la
  semántica REST y el modelo de direccionamiento RF-05a.
- **Por qué la suite no lo detectó:** el caso `CRUD-04` creaba con IPv4 y hacía PUT con IPv4
  de nuevo; nunca ejercitaba un campo **omitido**.

### Corrección

- `replace()` ahora invoca `applyUpdate(..., fullReplace=true)`: en reemplazo completo, los
  campos opcionales omitidos (`managementIpv4`/`managementIpv6`, `vendor`, `model`,
  `assetTag`, `location`) se ponen en `null`. Se mantiene la invariante RF-05a (al menos una
  dirección de gestión): un PUT que dejaría el dispositivo sin ninguna dirección → `422`
  `ADDRESS_INVALID`. `update()` (PATCH) conserva la semántica de merge (`fullReplace=false`).
- **Tests de regresión añadidos** (`DeviceCertificationIT`): `CRUD-04b`
  (`replace_isFullReplacement_clearsOmittedFields`) y `CRUD-04c`
  (`replace_withNoManagementAddress_returns422`). Suite: **98 → 100 tests**.
- **Verificación en vivo tras el fix:** PUT con solo IPv6 → `managementIpv4: null`,
  `vendor: null`, `model: null`, `managementIpv6` fijado. ✅
- **Blast radius:** **local** (lógica interna de `asset-inventory-service`); no cambia el
  contrato de API (mismo esquema `DeviceUpdateFull`) ni el catálogo de eventos. Gatekeeper en
  verde (100 tests, cobertura ≥ 70 %, lint).

---

## Cómo reproducir

1. `cd codigo/backend && docker compose -f docker-compose.dev.yml up --build`
2. Importar la colección Postman (`asset-inventory-service/postman/`) o usar los `curl` de la guía
   [`../postman/GUIA_PRUEBAS_POSTMAN.md`](../postman/GUIA_PRUEBAS_POSTMAN.md).
3. Ejecutar en orden: token (ADM) → alta → consulta → listar → PATCH → PUT → baja → bulk → job.
4. Repetir consulta con token AUD para ver la redacción; probar OPE (403) y sin token (401).

> **Nota:** este es el reporte **técnico** (para desarrolladores). La presentación de negocio para el
> cliente vive en el guion de UAT y su demo guiada:
> `../../../management/documentos/uat/asset-inventory-service/`.
