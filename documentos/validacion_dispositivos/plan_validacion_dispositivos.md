# Plan maestro de validación de fidelidad con dispositivos de red

> **Documento de plan (Fase 0).** Define **cómo** redSegura valida que sus servicios que hablan por
> SSH con equipos de red (`config-backup`, y a futuro `scan-orchestrator`, `telemetry-collector`)
> funcionan **fielmente contra los dispositivos reales más usados en la industria**. Es la fuente de
> verdad de la estrategia; los **procedimientos ejecutables** (uno por nivel) y sus **resultados** se
> documentan en archivos aparte de esta misma carpeta.
>
> **Estado:** vigente · **Fecha:** 2026-07-26 · **Aplica a:** servicios con conector SSH (RF-06/07/09/10).

---

## 1. Objetivo

Garantizar que la captura de configuración, el versionado y la detección de *drift* de redSegura
producen resultados **correctos y fieles** cuando se ejecutan contra los **sistemas operativos de red
(NOS) reales** de los fabricantes más usados en la industria —no solo contra dobles de prueba—, y
dejar esa fidelidad **medida, versionada y con criterios de aprobación explícitos** antes de producción.

## 2. Problema que resuelve

Los tests automatizados **mockean** el dispositivo (práctica correcta: herméticos, sin red, sin
secretos). Eso es necesario pero **insuficiente**: un mock no reproduce el comportamiento del NOS real
que puede **romper la captura o falsear el resultado**:

- **Paginación** (`--More--`) que contamina el archivo si no se envía `terminal length 0`.
- **Banner/MOTD** y líneas de ruido (`Building configuration...`, `Current configuration : N bytes`).
- **Detección de prompt** y **escalada de privilegio** (`enable`, `configure`) por plataforma.
- **Comandos distintos por NOS**: `show running-config` (Cisco/Arista) vs `show configuration`
  (Junos/VyOS) vs `info` (Nokia SR Linux).
- **SSH heredado** (algoritmos KEX/cifrado antiguos), límites de sesión, **timeouts** (RNF-10).
- **Secretos** en la config (`enable secret`, `snmp community`) — cómo aparecen y que **no se filtren**
  en logs (RNF-17).

Estos fallos **solo se detectan contra el NOS real**. Este plan los caza de forma escalonada.

## 3. Justificación

- **Es la propuesta de valor central** de `config-backup` (RF-06): respaldar configuraciones reales.
  Sin validar contra NOS reales, "funciona" es una hipótesis.
- **redSegura debe ser abierto y multi-vendor** (decisión de producto 2026-07-25): el alcance de la
  matriz son los **dispositivos más usados de la industria**, no el inventario de un cliente.
- Cierra el hueco de fidelidad registrado en `preparacion_produccion.md §2.9` con herramienta y etapa
  adecuadas a cada propósito.

## 4. Alcance

**Plataformas objetivo (matriz multi-vendor "amplia").** Prioridad por uso en la industria:

| Plataforma (NOS) | `device_type` netmiko | Comando(s) de config | Fuente de validación prevista |
|---|---|---|---|
| Cisco IOS (clásico) | `cisco_ios` | `show running-config` / `show startup-config` | GNS3/CML (IOSv) — licenciado |
| Cisco IOS-XE | `cisco_xe` | idem | DevNet Sandbox / CML (Cat8000v) |
| Cisco NX-OS | `cisco_nxos` | idem | DevNet / Nexus 9000v |
| Cisco ASA | `cisco_asa` | `show running-config` / `show startup-config` | lab / hardware |
| Juniper Junos | `juniper_junos` | `show configuration \| display set` | vLabs / vSRX |
| Arista EOS | `arista_eos` | `show running-config` / `show startup-config` | cEOS (contenedor) |
| Nokia SR Linux | `nokia_srl` | `info` (config declarativa) | contenedor (Containerlab) |
| VyOS (open) | `vyos` | `show configuration commands` | contenedor |

> **Fuera de alcance de este plan:** la lógica de negocio ya certificada de cada servicio (cubierta por
> sus tests/QA 4 fases) y las interacciones entre microservicios (cubiertas por los *golden paths*,
> RNF-32). Aquí solo se valida la **fidelidad con el dispositivo**.

## 5. Estándares y buenas prácticas de cumplimiento

- **Gestión de configuración de red (config-as-code):** alineado con las herramientas de referencia de
  la industria **RANCID** y **Oxidized** — se almacena la config **completa** (para restaurar/auditar),
  con el control de secretos en la **capa de almacenamiento y de exposición**, no borrándolos
  (`preparacion_produccion.md §2.10`).
- **Automatización de red:** uso de **Netmiko** (conexión SSH multi-vendor) y, para la abstracción
  multi-vendor de recuperación de config, evaluación de **NAPALM** (`get_config()` uniforme entre
  drivers) — ver §11 (implicación de diseño).
- **Fidelidad de emulación:** imágenes de **NOS reales** en emuladores de alta fidelidad
  (**GNS3 / CML / EVE-NG / Containerlab**); **nunca Packet Tracer** para automatización.
- **Seguridad:** alcance de conexión restringido a CIDRs autorizados (**RNF-07**); credenciales
  externalizadas (**RNF-06**); sin secretos en logs (**RNF-17**). Las capturas de fixtures se
  **redactan** antes de versionarse (ver guía de captura).
- **Trazabilidad:** cada nivel produce un **reporte con resultados por plataforma** (tono ejecutivo),
  versionado; la **matriz de compatibilidad** es el artefacto de aprobación de go-live.

## 6. La escalera de validación (3 niveles)

| Nivel | Qué valida | Herramienta | Costo | Automatizable | Etapa |
|---|---|---|---|---|---|
| **1 · Fixtures de output real** | Que el **parsing/manejo** del output real es correcto (banner, paginación, `end`, running vs startup, secretos) | Capturas reales grabadas → **replay** contra el conector/lógica | Gratis | ✅ total (CI) | **DEV** |
| **2a · NOS libre emulado** | El **mecanismo completo** SSH→fetch→Git→drift contra un **NOS real** contenedor-nativo (no-Cisco) | **Containerlab / Docker** + NOS libre | Gratis | ✅ | **DEV/INT-SYNC** |
| **2b · NOS Cisco emulado** | El mecanismo completo contra **Cisco real** (IOSv/IOS-XE/NX-OS) | **GNS3/CML** (IOSv) · **DevNet Sandbox** (IOS-XE/NX-OS) | Gratis (DevNet) a licenciado (CML) | Semi (API) | **INT-SYNC/PRE-REL** |
| **3 · Hardware representativo** | Fidelidad final contra equipos físicos representativos | Equipo real | Su red | Manual | **PRE-REL/DEPLOY** |

## 7. Matriz de compatibilidad (entregable de aprobación)

Se mantiene en [`matriz_compatibilidad.md`](matriz_compatibilidad.md) (se crea al ejecutar). Por cada
plataforma objetivo (§4) registra el resultado de cada criterio (§8) y su estado
(`✅ validado · 🟡 en curso · 🔵 pendiente · ⬜ no aplica`). **Gate de go-live:** verde para las
plataformas en alcance.

## 8. Criterios de éxito (por plataforma)

1. **Backup exitoso:** running y startup capturados, **no vacíos, bien formados** (sin `--More--` ni
   basura de paginación; con marcador de fin p. ej. `end` donde aplique).
2. **`unsavedChanges` correcto:** running ≠ startup ⇒ `true`; iguales ⇒ `false`.
3. **Drift detectado:** tras cambiar la config en vivo, un `drift-check` reporta drift; sin cambios, no.
4. **Prompt/paginación/privilegio** manejados (enable/config, `terminal length 0` o equivalente).
5. **Resiliencia (RNF-10):** timeout/host inalcanzable → `FAILED` acotado (no cuelga), con reintentos.
6. **Seguridad:** secretos del dispositivo **no** aparecen en logs (RNF-17); conexión solo a CIDRs
   autorizados (RNF-07).
7. **Idempotencia operativa:** respaldar dos veces sin cambios no genera commits espurios de contenido.

## 9. Resultados esperados

- **Nivel 1:** el conector/lógica procesan output real de cada plataforma sin corromper el archivo
  (fixtures → replay verde). Cualquier discrepancia = hallazgo → fix + test de regresión.
- **Nivel 2a/2b:** un respaldo **realmente exitoso** contra un NOS real (Git con la config, drift
  detectado). Evidencia viva de RF-06/07/09/10 end-to-end.
- **Matriz de compatibilidad** verde para las plataformas en alcance = criterio de go-live cumplido.

## 10. Procedimiento desglosado (visión general)

Cada nivel tiene su **procedimiento ejecutable** (archivo propio, con comandos/herramientas/pasos y su
**reporte de resultados** en tono ejecutivo):

| Nivel | Procedimiento | Reporte de resultados |
|---|---|---|
| 1 | `procedimiento_nivel1_fixtures.md` (+ `guia_captura_fixtures.md` para el insumo) | dentro del procedimiento (§ resultados) |
| 2a | `procedimiento_nivel2a_nos_libre.md` | idem |
| 2b | `procedimiento_nivel2b_cisco_gns3.md` | idem |

Flujo común de cada procedimiento: **objetivo → precondiciones → pasos (comando por comando) → cómo se
verifica → criterios → resultados (ejecutivo) → hallazgos/blast radius → veredicto**.

## 11. Implicación de diseño: soporte multi-vendor del conector

"Ser lo más compatible posible" exige que `config-backup` seleccione el **`device_type` por
dispositivo** (hoy usa uno **global**, `CBS_SSH_DEVICE_TYPE`). La plataforma de cada equipo la conoce
`asset-inventory` (`deviceType`/`vendor`/`model`) y viaja por los eventos `asset.*`. Opciones:

- **Corto plazo:** el conector acepta un `device_type` por dispositivo (derivado del `vendor`/`model`
  de la vista local); comandos de fetch por plataforma (mapa vendor→comandos).
- **Recomendado a evaluar:** adoptar **NAPALM** para la recuperación de config (`get_config()` uniforme
  entre drivers), reduciendo el mapa de comandos por vendor.

Esta mejora es **prerrequisito del Nivel 2a** (NOS libre no-Cisco) y del alcance multi-vendor; se
aborda como tarea de diseño acotada antes/junto con la ejecución de ese nivel. Se registra como deuda
con disparador **INT-SYNC** hasta implementarse.

## 12. Riesgos y consideraciones

- **Alcance de red (RNF-07):** la IP de gestión del equipo emulado debe estar en `CBS_ALLOWED_SCAN_CIDRS`;
  jamás apuntar a redes de terceros no autorizadas.
- **Secretos en fixtures:** las capturas reales **se redactan** antes de versionarse (secretos →
  valores ficticios del **mismo formato**), para preservar la estructura sin exponer credenciales.
- **Licencias:** imágenes Cisco (IOSv/IOS-XE) son licenciadas → CML/GNS3 (lab) o DevNet Sandbox
  (gratis) según plataforma; no redistribuibles en CI.
- **Reachability GNS3:** el Nivel 2b corre contra un GNS3 en GCP vía túnel VPN → coordinación de red
  específica (ver el procedimiento del Nivel 2b).

## 13. Gobernanza (gates y disparadores)

- **Nivel 1** → **DEV**: los replay tests corren en el gate del servicio (parte de la suite).
- **Nivel 2a** → **DEV/INT-SYNC**: smoke automatizable; se agenda con la mejora multi-vendor.
- **Nivel 2b / Nivel 3** → **PRE-REL/DEPLOY**: matriz de compatibilidad como criterio de go-live.
- La **matriz de compatibilidad** es el artefacto versionado de aprobación; su verde para las
  plataformas en alcance es requisito de producción (`preparacion_produccion.md §2.9`).

## 14. Entregables y ubicación

```
backend/documentos/validacion_dispositivos/
├── plan_validacion_dispositivos.md      ← este documento (Fase 0)
├── guia_captura_fixtures.md             ← insumo del Nivel 1 (qué capturar y cómo enviarlo)
├── matriz_compatibilidad.md             ← entregable de aprobación (se llena al ejecutar)
├── procedimiento_nivel1_fixtures.md     ← Nivel 1 (replay) + resultados
├── procedimiento_nivel2a_nos_libre.md   ← Nivel 2a (NOS libre) + resultados
├── procedimiento_nivel2b_cisco_gns3.md  ← Nivel 2b (IOSv/GNS3) + resultados
└── fixtures/                            ← capturas reales redactadas (por plataforma)
```
Referenciado desde `management/documentos/arquitectura/preparacion_produccion.md §2.9`.
