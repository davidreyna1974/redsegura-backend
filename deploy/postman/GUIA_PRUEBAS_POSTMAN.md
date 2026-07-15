# Guía de pruebas con Postman — `asset-inventory-service`

Guía paso a paso para probar manualmente el microservicio **asset-inventory-service**
contra un entorno de desarrollo real (PostgreSQL + RabbitMQ + Keycloak + el servicio),
usando la colección de Postman incluida en este repositorio.

> **Qué es y qué NO es.** Este es un entorno de **desarrollo local** para pruebas
> exploratorias/de aceptación manual (líder de proyecto, demo, QA manual). **No es
> producción** (contraseñas triviales, Keycloak en `start-dev`). La verificación
> automatizada la ejecuta el gatekeeper (`mvn verify`) y el CI; esta guía es para
> validar el servicio "con las manos".

---

## 1. Arquitectura de la prueba

- El **servicio y sus dependencias corren en contenedores Docker** (Docker Desktop en tu
  Mac los gestiona; no se instala nada nativo).
- **Postman corre nativo en tu Mac** y se conecta a los contenedores por `localhost`
  (Docker publica los puertos).

```
┌─ Tu Mac ─────────────────────────────────────────────────┐
│                                                           │
│  Postman  ──HTTP──►  localhost:8080  (Keycloak)  ─┐        │
│     │                localhost:8081  (servicio)   │        │
│     │                                             │        │
│     └──────────────── Docker Desktop ─────────────┘        │
│        ┌──────────┬──────────┬──────────┬──────────────┐  │
│        │ postgres │ rabbitmq │ keycloak │ asset-inventory│ │
│        └──────────┴──────────┴──────────┴──────────────┘  │
└───────────────────────────────────────────────────────────┘
```

---

## 2. Requisitos previos

| Requisito | Comprobación |
|---|---|
| **Docker Desktop** corriendo | `docker info` no da error |
| **Postman** (app de escritorio) | instalada |
| `jq` (opcional, solo para la vía curl) | `jq --version` |

---

## 3. Levantar el entorno

En una terminal (déjala abierta; ahí se ven los logs):

```bash
cd "codigo/backend"
docker compose -f docker-compose.dev.yml up --build
```

La primera vez tarda (compila el servicio en una imagen multi-stage e importa el realm de
Keycloak). El entorno está listo cuando en los logs aparece que **asset-inventory** arrancó
(`Started AssetInventoryApplication`), lo cual ocurre **después** de que Postgres, RabbitMQ y
Keycloak (realm `redsegura` importado) están sanos.

Servicios expuestos:

| Servicio | URL | Notas |
|---|---|---|
| asset-inventory-service | `http://localhost:8081` | la API bajo prueba |
| Keycloak | `http://localhost:8080` | emisor de los JWT (admin: `admin`/`admin`) |
| RabbitMQ (consola) | `http://localhost:15672` | `guest`/`guest` — para ver los eventos `asset.*` |
| PostgreSQL | `localhost:5432` | `asset_inventory` / `redsegura` / `changeme` |

**Verificación rápida (sin token):**
```bash
curl -s http://localhost:8081/api/v1/health/readiness   # -> {"status":"UP"}
```

---

## 4. Importar la colección en Postman

1. Postman → **Import** → arrastra
   `deploy/postman/redsegura-asset-inventory.postman_collection.json`.
2. **No hace falta crear un Environment.** La colección ya trae sus variables apuntando a
   `localhost`:

   | Variable | Valor por defecto | Se llena |
   |---|---|---|
   | `base_url` | `http://localhost:8081` | fijo |
   | `keycloak_url` | `http://localhost:8080` | fijo |
   | `client_id` | `redsegura-postman` | fijo |
   | `access_token` | *(vacío)* | automático, al pedir un token |
   | `deviceId` | *(vacío)* | automático, al registrar un dispositivo |
   | `etag` | *(vacío)* | automático, al consultar/registrar |
   | `jobId` | *(vacío)* | automático, al lanzar importación masiva |

   Los **scripts de test** de cada request encadenan estas variables solos: por eso basta
   con seguir el orden.

---

## 5. Usuarios de prueba (realm `redsegura`)

| Usuario | Contraseña | Rol | Puede |
|---|---|---|---|
| `admin` | `password` | **ADM** | todo: alta / edición / baja / importación + lectura |
| `operador` | `password` | **OPE** | **solo lectura** |
| `auditor` | `password` | **AUD** | solo lectura, con `mgmtIp` **enmascarada** |

> El servicio es un **OAuth2 Resource Server**: **todos** los `/api/v1/devices/**` exigen un
> JWT válido de Keycloak con el rol en `realm_access.roles`. Sin token, solo responden
> `/api/v1/health/**` y `/actuator/**`. La autorización se valida **en el propio servicio**,
> no se confía en que el Gateway ya filtró (RBAC de extremo a extremo).

---

## 6. Flujo de ejecución (camino feliz)

Ejecuta en este orden. La respuesta esperada de cada request está **guardada como ejemplo**
en la propia colección (pestaña *Examples* del request).

| # | Carpeta › Request | Método | Esperado | Efecto |
|---|---|---|---|---|
| 1 | **Auth › Token (Administrador)** | POST | `200` | guarda el JWT en `{{access_token}}` |
| 2 | **Dispositivos › Registrar dispositivo (IPv4)** | POST | `201` + `ETag` | guarda `{{deviceId}}` y `{{etag}}` |
| 3 | **Dispositivos › Consultar dispositivo** | GET | `200` | devuelve el device; refresca `{{etag}}` |
| 4 | **Dispositivos › Listar / buscar (por hostname)** | GET | `200` | página con el device |
| 5 | **Dispositivos › Editar (PATCH, requiere If-Match)** | PATCH | `200` | usa `{{etag}}`; refresca `{{etag}}` |
| 6 | **Dispositivos › Dar de baja (DELETE, requiere If-Match)** | DELETE | `204` | baja lógica |

**Cuerpo de ejemplo (paso 2, IPv4):**
```json
{
  "serialNumber": "FCW-001",
  "hostname": "SW-CORE-1",
  "managementIpv4": { "address": "10.0.0.11", "prefixLength": 24, "gateway": "10.0.0.1" },
  "deviceType": "SWITCH",
  "vendor": "Cisco",
  "model": "Catalyst 9300",
  "criticality": "ALTA"
}
```

**Direccionamiento dual-stack (IPv4 / IPv6 / ambas).** La request
**Registrar dispositivo (IPv6)** demuestra el registro solo-IPv6; la IPv6 se **canonicaliza**
(RFC 5952) en la respuesta:
```json
{ "serialNumber": "FCW-004", "hostname": "SW-V6",
  "managementIpv6": { "address": "2001:0DB8:ACAD:1::11", "prefixLength": 64 },
  "deviceType": "ROUTER", "criticality": "MEDIA" }
// -> managementIpv6.address se devuelve como "2001:db8:acad:1::11"
```
Se admite **IPv4 sola, IPv6 sola o ambas** (al menos una es obligatoria).

**Importación masiva (asíncrona):** *Importación masiva* devuelve `202` + `{{jobId}}`;
*Estado del job de importación* consulta el progreso hasta `COMPLETED`.

---

## 7. Verificar seguridad y RBAC (casos "sad" — muy recomendado)

Estos casos demuestran que el control de acceso se aplica de verdad:

**a) Redacción de IP por rol (dato sensible enmascarado para Auditor)**
1. **Auth › Token (Auditor)** → sobrescribe `{{access_token}}` con rol AUD.
2. **Dispositivos › Consultar dispositivo** → la `mgmtIp` viene **enmascarada**:
   `"address": "10.0.0.***"` (IPv4) / `"...:***"` (IPv6). Con ADM/OPE se ve completa.

**b) Operador no puede escribir (solo lectura → `403`)**
1. **Auth › Token (Operador)**.
2. **Registrar dispositivo (IPv4)** o **Dar de baja** → **`403 Forbidden`** en
   `application/problem+json` (RFC 7807), sin filtrar internos.

**c) Sin token → `401`**
1. Borra el valor de `{{access_token}}` (o usa un request sin `Authorization`).
2. Cualquier `/api/v1/devices/**` → **`401 Unauthorized`**.

**d) Baja sin `If-Match` → `428` / con ETag viejo → `412`**
- Editar/borrar exige `If-Match` con el ETag vigente (bloqueo optimista, ADR-09).

---

## 8. Ejecutar todo de una vez (Collection Runner)

Botón **Run** de la colección → selecciona la carpeta **Auth** y **Dispositivos** en orden →
**Run**. Postman ejecuta la secuencia y muestra los tests (aserciones) en verde/rojo.
Ideal para una demo o una pasada de humo rápida.

---

## 9. Ver los eventos publicados (opcional)

Cada alta/edición/baja publica un evento `asset.*` vía **transactional outbox** a RabbitMQ.
En `http://localhost:15672` (guest/guest) → **Queues** puedes ver el tráfico del exchange
`redsegura.events`. (Aún no hay consumidor real; llegará con `config-backup-service`.)

---

## 10. Alternativa por línea de comandos (curl)

```bash
# 1) Token de administrador
TOKEN=$(curl -s -X POST \
  http://localhost:8080/realms/redsegura/protocol/openid-connect/token \
  -d grant_type=password -d client_id=redsegura-postman \
  -d username=admin -d password=password | jq -r .access_token)

# 2) Registrar
curl -s -X POST http://localhost:8081/api/v1/devices \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"serialNumber":"FCW-001","hostname":"SW-CORE-1",
       "managementIpv4":{"address":"10.0.0.11","prefixLength":24,"gateway":"10.0.0.1"},
       "deviceType":"SWITCH","criticality":"ALTA"}'

# 3) Listar
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/devices
```

---

## 11. Resolución de problemas

| Síntoma | Causa probable | Solución |
|---|---|---|
| `Connection refused` a `:8081` | el servicio aún no arrancó | espera a `Started AssetInventoryApplication` en los logs |
| `401` con token recién pedido | el token expiró (1 h) o `{{access_token}}` vacío | vuelve a ejecutar **Token (…)** |
| `409` al registrar | serial/hostname/IP ya existen (únicos) | cambia el `serialNumber`/`hostname`/IP o baja el `-v` (§12) |
| Token da `401`/`invalid_client` | Keycloak aún importando el realm | espera unos segundos y reintenta |
| Puerto `8080`/`8081` ocupado | otro proceso lo usa | libera el puerto o ajusta el mapeo en `docker-compose.dev.yml` |

---

## 12. Parar el entorno

```bash
# Ctrl-C en la terminal del compose, luego:
docker compose -f docker-compose.dev.yml down       # conserva los datos
docker compose -f docker-compose.dev.yml down -v     # borra los datos (BD limpia)
```

---

## Referencias

- Entorno de desarrollo: [`../README.md`](../README.md)
- Colección: [`redsegura-asset-inventory.postman_collection.json`](redsegura-asset-inventory.postman_collection.json)
- Realm sembrado: [`../keycloak/redsegura-realm.json`](../keycloak/redsegura-realm.json)
- Contrato de la API: [`../../asset-inventory-service/openapi.yaml`](../../asset-inventory-service/openapi.yaml)
- Casos de prueba del módulo:
  [`../../asset-inventory-service/documentos/casos_de_prueba.md`](../../asset-inventory-service/documentos/casos_de_prueba.md)
