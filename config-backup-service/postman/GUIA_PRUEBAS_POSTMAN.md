# Guía de pruebas con Postman — config-backup-service

Cómo probar `config-backup-service` de punta a punta con **JWT reales de Keycloak** sobre el entorno
de desarrollo. Complementa la verificación documentada en
[`../documentos/verificacion_endpoints.md`](../documentos/verificacion_endpoints.md).

## 1. Levantar el entorno

Desde `codigo/backend/`:

```bash
docker compose -f docker-compose.dev.yml up -d --build postgres rabbitmq keycloak config-backup
```

| Componente | URL |
|---|---|
| config-backup-service | http://localhost:8082/api/v1 |
| Swagger UI (contrato en runtime) | http://localhost:8082/docs |
| Métricas Prometheus | http://localhost:8082/metrics |
| Keycloak (admin/admin) | http://localhost:8080 |
| RabbitMQ management (guest/guest) | http://localhost:15672 |

Espera a que `GET /api/v1/health/readiness` responda **200** (BD migrada + broker + Git listos).

## 2. Importar la colección

Importa `redsegura-config-backup.postman_collection.json`. Trae variables ya configuradas
(`base_url`, `keycloak_url`, `client_id`, `deviceId`…) y **autenticación Bearer a nivel de colección**
con `{{access_token}}`.

## 3. Obtener un token

Ejecuta **Auth › Token (Administrador)** (o `Operador`/`Auditor`). El test guarda el JWT en
`{{access_token}}` automáticamente; todos los demás requests lo reutilizan.

| Usuario | Contraseña | Rol | Puede |
|---|---|---|---|
| `admin` | `password` | ADM | todo (respaldos, drift, **crear schedules**, lectura) |
| `operador` | `password` | OPE | respaldos, drift, lectura (no crea schedules) |
| `auditor` | `password` | AUD | solo lectura |

> Para probar RBAC (403), pide un token con un rol de menos privilegio y repite una operación de
> escritura. El token vive ~5 min; si empiezas a ver 401, vuelve a ejecutar el request de token.

## 4. Sembrar un dispositivo en la vista (opcional, para endpoints por-dispositivo)

Este servicio **no** tiene alta de dispositivos: mantiene su vista a partir de eventos `asset.*`.
Para probar `POST /devices/{id}/backups` o `drift-check` con un objetivo real, publica un evento
`asset.created` al broker (o levanta `asset-inventory` y da de alta un dispositivo ahí). Ejemplo
rápido con el propio contenedor:

```bash
docker exec redsegura-dev-config-backup-1 python -c "
import pika, json, uuid
ev={'eventId':str(uuid.uuid4()),'eventType':'asset.created','version':'1.1.0','occurredAt':'2026-01-01T00:00:00Z','source':'postman','payload':{'deviceId':'11111111-1111-1111-1111-111111111111','hostname':'SW-1','managementIpv4':{'address':'10.0.0.21','prefixLength':24,'gateway':None},'criticality':'ALTA','status':'ACTIVO'}}
ch=pika.BlockingConnection(pika.URLParameters('amqp://guest:guest@rabbitmq:5672/')).channel()
ch.basic_publish(exchange='redsegura.events',routing_key='asset.created',body=json.dumps(ev).encode())
print('ok')"
```

El `deviceId` de la variable de la colección ya apunta a ese UUID.

> **Nota (dev sin dispositivos reales):** el respaldo intentará SSH a `10.0.0.21`. Sin un dispositivo
> real detrás, terminará en `FAILED` (RF-10, comportamiento correcto) y el job por lotes igual llega
> a `COMPLETED`. El camino SSH **exitoso** se cubre con los tests automatizados (dobles).

## 5. Recorrido sugerido

1. **Salud y observabilidad** (sin token): liveness/readiness/metrics → 200.
2. **Auth › Token (Administrador)**.
3. **Programaciones**: crear (201) · cron inválido (400) · Operador (403) · listar (200).
4. **Respaldos**: por lotes `scope=all` (202 + `jobId`) → estado del job (200, `COMPLETED`) ·
   target inválido (422) · historial (200).
5. **Drift**: individual (200) · por lotes (202).

Los códigos esperados están en la descripción de cada request y verificados en su pestaña **Tests**.
