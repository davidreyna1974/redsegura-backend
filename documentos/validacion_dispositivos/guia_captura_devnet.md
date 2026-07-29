# Guía de captura sobre Cisco DevNet Sandbox (Nivel 1) — fuente Cisco real y gratuita

> **Para quién:** tú (operador). **Qué resuelve:** de dónde obtener output **Cisco real** para los
> fixtures del [Nivel 1](plan_validacion_dispositivos.md) **sin hardware ni licencias**, usando los
> **DevNet Sandbox always-on** de Cisco (dispositivos Cisco reales, gratis). Complementa la
> [`guia_captura_fixtures.md`](guia_captura_fixtures.md) (qué capturar / cómo redactar / cómo
> enviármelas); **esta** guía cubre el **acceso a DevNet** y el **procedimiento paso a paso** sobre él.
>
> **Estado:** vigente · **Fecha:** 2026-07-29 · **Aplica a:** Nivel 1 (fixtures) del plan de validación.

---

## 0. Respuesta a tu duda: ¿sirve mi cuenta de NetAcad?

**No directamente.** Cisco maneja **dos identidades separadas**:

| Servicio | Sistema de cuenta | ¿Sirve para DevNet? |
|---|---|---|
| **Cisco Networking Academy (NetAcad)** | `id.netacad.com` (identidad propia) | ❌ No (es otra base de identidad) |
| **DevNet Sandbox** | **Cuenta Cisco.com (CCO ID)** | ✅ Sí — es la que se necesita |

**Ambas son gratuitas.** Si no tienes una cuenta Cisco.com, se crea gratis en minutos (paso §2). El
uso de los sandbox **always-on** también es **gratuito** y **no consume licencias**.

> Nota: es común tener el mismo correo en ambos sistemas y aun así ser cuentas distintas. Si tu login
> de NetAcad no entra en `developer.cisco.com`, simplemente **registra una cuenta Cisco.com** con tu
> correo (§2) — no hay conflicto.

---

## 1. Qué modalidad de sandbox usamos (y por qué)

DevNet ofrece dos tipos; para el **Nivel 1** nos basta el más simple:

| Tipo | Reserva | VPN | Acceso | Uso en el Nivel 1 |
|---|---|---|---|---|
| **Always-On** | ❌ no | ❌ no | Compartido, alcanzable por internet (SSH/HTTPS) | ✅ **Por defecto** — capturamos `show run`/`show start` |
| **Reserved** | ✅ sí (hasta 7 días) | ✅ sí (Cisco Secure Client / AnyConnect) | Privado, admin completo | Solo para el fixture **running ≠ startup** (§6) |

Para grabar configuraciones (comandos de **lectura**), el **always-on alcanza y sobra**.

---

## 2. Acceso a DevNet — paso a paso

1. Abre **https://developer.cisco.com/sandbox** y pulsa **"Get Started with Sandbox"** (o **Log In**,
   arriba a la derecha).
2. Inicia sesión con tu **cuenta Cisco.com (CCO)**. Si no tienes:
   - En la pantalla de login, **"Create account"** → correo, nombre, país, contraseña → confirma el
     correo. (Es la cuenta Cisco.com, **no** NetAcad.)
3. Ya dentro, verás el **catálogo de sandboxes**. Filtra/busca los **Always-On** (§3).

> No se requiere tarjeta de crédito ni licencia. El acceso always-on no pide VPN.

---

## 3. Catálogo de sandboxes que cubren nuestro alcance

Cisco **renovó** los always-on: cada lanzamiento **auto-genera credenciales únicas** (usuario/clave
AAA, probadas por SSH antes de mostrártelas en el portal). **No uses credenciales "estáticas" de blogs
viejos**: toma siempre las que muestra el portal tras lanzar.

| Plataforma | `device_type` netmiko | Sandbox DevNet (always-on) | Host SSH (referencia) | Puerto |
|---|---|---|---|---|
| **Cisco IOS-XE** (Catalyst 8000v) | `cisco_xe` | *Catalyst 8000 / IOS XE Always-On* | `devnetsandboxiosxec8k.cisco.com` | 22 |
| **Cisco IOS-XE** (Catalyst 9000) | `cisco_xe` | *Catalyst 9000 Always-On* (read-only) | (mostrado en el portal) | 22 |
| **Cisco NX-OS** (Nexus 9000v) | `cisco_nxos` | *Open NX-OS Programmability Always-On* | `sbx-nxos-mgmt.cisco.com` | 22 |

> El **host y las credenciales exactas** para **tu** sesión salen en la pestaña **"Quick Access"** del
> sandbox tras lanzarlo — esos son los válidos. Los de arriba son solo referencia de continuidad.

**Cobertura de DevNet gratis:** **IOS-XE** y **NX-OS**. Quedan **fuera de DevNet** (van por GNS3/CML en
el Nivel 2b): **IOS clásico (IOSv, `cisco_ios`)** y **ASA (`cisco_asa`)** — sus imágenes son
licenciadas y no están como always-on.

---

## 4. Lanzar el sandbox y obtener credenciales

1. En el catálogo, entra al tile del sandbox deseado (p. ej. *Catalyst 8000 Always-On*).
2. Pulsa **Launch** y elige la **duración** (las credenciales viven ese lapso).
3. Espera a **Active** (1–2 min). En **"Quick Access"** verás: **host/URL**, **usuario**, **contraseña**
   y **puerto SSH (22)**. Cópialos.

> El always-on da **privilegio 15** (lectura/escritura) pero es **compartido y monitoreado**: **no**
> modifiques la interfaz de gestión ni el AAA (el monitor revierte el equipo a config base si se rompe
> la conectividad). Para el Nivel 1 solo **leemos** — no hay riesgo.

---

## 5. Captura de fixtures (comandos exactos sobre DevNet)

Conéctate por SSH con los datos del portal:

```bash
ssh <usuario_del_portal>@<host_del_portal>
# ejemplo IOS-XE: ssh <usuario>@devnetsandboxiosxec8k.cisco.com
```

Una vez dentro, **desactiva paginación** y captura las dos configuraciones:

**Cisco IOS-XE (`cisco_xe`) y NX-OS (`cisco_nxos`)** — estilo Cisco:
```
terminal length 0
show running-config
show startup-config
```

> Guarda la salida **completa** de cada comando en un archivo de texto (copia del terminal o
> `ssh ... "command" > archivo.txt`). Nómbralos con la convención de la guía general:
> `<device_type>__<host>__<running|startup>.txt`
> (p. ej. `cisco_xe__devnet-c8k__running.txt`, `cisco_nxos__devnet-n9kv__startup.txt`).

**Bonus de fidelidad (opcional, muy útil):** pásame **un** log crudo de la sesión SSH completa de un
equipo **con la paginación activada** (es decir, **sin** `terminal length 0`, dejando que aparezca
`--More--`). Sirve para verificar que el parser tolera el ruido de paginación real.

---

## 6. Fixture "oro": running ≠ startup (`unsavedChanges=true`)

El always-on es de **solo lectura efectiva** (revierte cambios), así que ahí no puedes generar un
*delta* running vs startup. Para ese fixture —el que valida `unsavedChanges=true` con datos reales—
usa un **Reserved sandbox** (admin completo):

1. En el catálogo, reserva un sandbox **IOS-XE Reserved** → recibirás por correo los datos de **VPN**
   (Cisco Secure Client / AnyConnect) y el acceso.
2. Conéctate por VPN, entra por SSH y haz un **cambio inocuo sin guardar** (p. ej. `configure terminal`
   → `banner motd #REDSEGURA-TEST#` → `end`) — **no** ejecutes `write memory` / `copy run start`.
3. Captura **running** y **startup** (ahora difieren) con los comandos del §5.
4. Al terminar, **libera** la reserva.

> Con **una** captura de este tipo basta. Si te resulta engorroso, dímelo y ese criterio lo cubrimos
> con un fixture sintético derivado (menos fiel, pero suficiente como respaldo).

---

## 7. Seguridad — redacta antes de enviarme nada

Aplica **íntegra** la sección de redacción de secretos de la
[`guia_captura_fixtures.md §0`](guia_captura_fixtures.md): reemplaza cada secreto (`enable secret`,
`snmp community`, claves) por un **valor ficticio del mismo formato**, conservando prefijo/tipo
(`5`/`7`/`9`/`$1$`/`$9$`). No borres líneas: cámbiales solo el valor (RNF-06/17).

> Los sandbox DevNet son entornos de práctica, pero **igual se redacta** por disciplina y porque los
> fixtures se **versionan** en el repo.

---

## 8. Nota de alcance (RNF-07) — no aplica todavía

En el Nivel 1 **la captura la haces tú a mano**; `config-backup` **no** se conecta. Por eso el control
de alcance de red (RNF-07: CIDRs autorizados / red simulada) **no interviene aún** — DevNet vive en
internet y está bien, porque solo grabamos texto. RNF-07 entra en el **Nivel 2** (cuando el servicio
abre el SSH en vivo), y ahí sí coordinamos routing/CIDR.

---

## 9. Qué me pasas y qué hago yo

**Tú:** por cada plataforma DevNet que captures (idealmente **IOS-XE** y **NX-OS**), los dos archivos
(`running`/`startup`) redactados — pegados en el chat con encabezado claro o guardados en
`fixtures/` (ver [guía general §3–4](guia_captura_fixtures.md)).

**Yo:** los convierto en **fixtures versionados** + **tests de replay** contra el conector/lógica de
`config-backup`, verifico que no se corrompen (paginación, banner, `end`, running≠startup, secretos),
y te entrego el **reporte ejecutivo del Nivel 1** por plataforma + actualizo la matriz de
compatibilidad.

---

## 10. Resumen operativo (checklist)

```
[ ] 1. Cuenta Cisco.com (CCO) creada / login OK en developer.cisco.com/sandbox   (NO NetAcad)
[ ] 2. Lanzado el Always-On IOS-XE (Catalyst 8000/9000) → copiadas credenciales (Quick Access)
[ ] 3. SSH OK → terminal length 0 → show running-config + show startup-config capturados
[ ] 4. (Repetir) Lanzado el Always-On NX-OS (Nexus 9000v) → misma captura
[ ] 5. (Opcional oro) Reserved IOS-XE + VPN → cambio sin guardar → captura running≠startup
[ ] 6. Secretos redactados (mismo formato) en TODAS las capturas
[ ] 7. Archivos nombrados <device_type>__<host>__<running|startup>.txt → enviados
```
