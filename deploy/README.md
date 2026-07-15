# Entorno de desarrollo local — asset-inventory-service

Levanta el servicio con sus dependencias reales para **probarlo vía Postman/curl**.
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
> **Guía paso a paso completa:** [`postman/GUIA_PRUEBAS_POSTMAN.md`](postman/GUIA_PRUEBAS_POSTMAN.md)
> (orden de ejecución, respuestas esperadas, casos RBAC/redacción, Collection Runner y troubleshooting).

Resumen:
1. Importa `deploy/postman/redsegura-asset-inventory.postman_collection.json`.
2. Ejecuta **Auth › Token (Administrador)** → guarda el JWT en `{{access_token}}`.
3. Ejecuta los requests de **Dispositivos** (guardan `deviceId`/`etag`/`jobId` solos).
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
