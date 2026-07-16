# Entorno de desarrollo local (infra compartida) — redSegura backend

Levanta los servicios con sus **dependencias reales compartidas** (PostgreSQL + RabbitMQ +
**Keycloak** sembrado) para **probarlos vía Postman/curl**. Es la infra común del backend; la
**colección Postman de cada servicio** vive con el servicio (`<servicio>/postman/`).
**No es producción** (contraseñas triviales, Keycloak en `start-dev`).

## Requisitos
- Docker + Docker Compose.

## Levantar
```bash
cd codigo/backend
docker compose -f docker-compose.dev.yml up --build
```
Servicios expuestos:
| Servicio | URL |
|---|---|
| asset-inventory-service | http://localhost:8081 |
| Keycloak (admin/admin) | http://localhost:8080 |
| RabbitMQ (guest/guest) | http://localhost:15672 |
| PostgreSQL | localhost:5432 (`asset_inventory` / `redsegura` / `changeme`) |

El servicio arranca cuando Postgres, RabbitMQ y **Keycloak (realm `redsegura` importado)** están sanos.

## Probar con Postman
La **colección Postman de cada servicio vive con el servicio** (en `<servicio>/postman/`), junto a su
guía paso a paso. Este entorno (Postgres + RabbitMQ + Keycloak) es la infra **compartida** que
levantan todas ellas.

- `asset-inventory-service` → [`../asset-inventory-service/postman/GUIA_PRUEBAS_POSTMAN.md`](../asset-inventory-service/postman/GUIA_PRUEBAS_POSTMAN.md)
  (+ colección `redsegura-asset-inventory.postman_collection.json`).

Resumen del flujo:
1. Importa la colección del servicio (`<servicio>/postman/*.postman_collection.json`).
2. Ejecuta **Auth › Token (Administrador)** → guarda el JWT en `{{access_token}}`.
3. Ejecuta los requests (guardan `deviceId`/`etag`/`jobId` solos).
   - Para ver la **redacción de IP**, obtén el **Token (Auditor)** y repite *Consultar dispositivo*.

## Usuarios de prueba (realm `redsegura`)
| Usuario | Contraseña | Rol | Puede |
|---|---|---|---|
| `admin` | `password` | ADM | todo (alta/edición/baja/bulk + lectura) |
| `operador` | `password` | OPE | solo lectura |
| `auditor` | `password` | AUD | solo lectura, con `mgmtIp` enmascarada |

## Obtener un token con curl (alternativa)
```bash
curl -s -X POST http://localhost:8080/realms/redsegura/protocol/openid-connect/token \
  -d grant_type=password -d client_id=redsegura-postman -d username=admin -d password=password \
  | jq -r .access_token
```
Luego: `curl -H "Authorization: Bearer <TOKEN>" http://localhost:8081/api/v1/devices`

## Parar
```bash
docker compose -f docker-compose.dev.yml down -v   # -v borra los datos
```

> El servicio es un OAuth2 Resource Server: **todos** los `/api/v1/devices/**` exigen un JWT válido de
> Keycloak con el rol en `realm_access.roles`. Sin token solo responden `/api/v1/health/**` y `/actuator/**`.
