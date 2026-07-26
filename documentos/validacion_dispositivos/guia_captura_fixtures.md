# Guía de captura de fixtures (Nivel 1) — output real de dispositivos

> **Para quién:** tú (operador con acceso a los equipos). **Objetivo:** obtener output **real** de
> `show running-config` / `show startup-config` (y equivalentes multi-vendor) para validar que
> `config-backup` procesa configuraciones reales sin corromperlas ni falsear resultados (Nivel 1 del
> [plan de validación](plan_validacion_dispositivos.md)). Yo convierto tus capturas en **fixtures** y
> **tests de replay** que corren en el gate.

---

## ⚠️ 0. Seguridad PRIMERO — redacta los secretos antes de enviarme nada

Las configuraciones contienen secretos. **Antes de compartirlas**, reemplaza cada secreto por un valor
**ficticio del mismo formato** (para conservar la estructura sin exponer credenciales — RNF-06/17). No
borres las líneas: **cámbiales solo el valor**. Checklist:

| Qué redactar | Ejemplo original | Reemplazo (mismo formato) |
|---|---|---|
| `enable secret` / hashes | `enable secret 9 $9$abc123...` | `enable secret 9 $9$REDACTED000...` |
| Contraseñas de usuario | `username admin secret 5 $1$xy$...` | `username admin secret 5 $1$xx$REDACTED` |
| SNMP communities | `snmp-server community S3cr3t RO` | `snmp-server community REDACTED_RO RO` |
| Claves pre-compartidas / crypto | `pre-shared-key local Abc!23` | `pre-shared-key local REDACTED` |
| Llaves TACACS/RADIUS/NTP | `key 7 070C2846...` | `key 7 070C2846REDACTED` |
| IPs/hostnames sensibles (opcional) | públicos reales | rangos de doc (`192.0.2.0/24`, `203.0.113.0/24`) |

> **Importante:** conserva el **tipo/prefijo** del secreto (`5`, `7`, `9`, `$1$`, `$9$`…) y la longitud
> aproximada — así el fixture reproduce el formato real que el parser debe tolerar. Lo que valoro es la
> **estructura** (banner, orden, comandos, marcadores de fin), no el secreto.

---

## 1. Qué capturar

Por cada dispositivo/plataforma que tengas a mano, dos archivos: **running-config** y
**startup-config** (o sus equivalentes multi-vendor). Con **paginación desactivada** (para que el
output salga completo, como lo recibe la automatización).

> **Bonus de fidelidad (opcional):** si puedes, pásame también **un** log crudo de la sesión SSH
> completa (con banner de login y, si aparece, la paginación) de **un** equipo — sirve para verificar
> el manejo de banner/`--More--`. No lo redactes distinto: aplica la misma redacción de secretos.

## 2. Comandos exactos por plataforma

**Cisco IOS / IOS-XE / NX-OS · Arista EOS** (estilo Cisco):
```
terminal length 0
show running-config
show startup-config
```

**Cisco ASA:**
```
terminal pager 0
show running-config
show startup-config
```

**Juniper Junos:**
```
set cli screen-length 0
show configuration | display set        (formato "set", el más fiel para diff)
show configuration                      (formato jerárquico, opcional)
```

**Nokia SR Linux:**
```
environment more false
info                                     (config declarativa)
info flat                                (formato "flat", útil para diff)
```

**VyOS:**
```
show configuration commands              (desde modo operacional)
```

## 3. Cómo enviármelas

Cualquiera de estas dos vías:

- **A) Pégalas en el chat**, una por bloque, con un encabezado claro por archivo:
  ```
  === PLATAFORMA: cisco_ios | HOST: lab-r1 | TIPO: running ===
  <contenido redactado>
  ```
- **B) Guárdalas en archivos** en esta carpeta y me dices que están:
  `backend/documentos/validacion_dispositivos/fixtures/<device_type>__<host>__<running|startup>.txt`

## 4. Convención de nombres (yo la aplico si me las pegas)

`<device_type netmiko>__<host o "anon">__<running|startup>.txt`
Ejemplos: `cisco_ios__lab-r1__running.txt`, `cisco_nxos__core-sw1__startup.txt`,
`juniper_junos__edge-fw__running.txt`.

## 5. ¿Cuántas y de qué plataformas?

- **Mínimo útil:** 1 plataforma (aunque sea 1 equipo) → desbloquea el Nivel 1.
- **Ideal (matriz multi-vendor):** una de cada `device_type` del plan §4 que tengas acceso
  (Cisco IOS/IOS-XE/NX-OS/ASA, Juniper, Arista, Nokia, VyOS).
- Si un equipo tiene **cambios sin guardar** (running ≠ startup), esa captura es **oro**: valida
  `unsavedChanges=true` con datos reales.

## 6. Qué haré con ellas (para que sepas el resultado esperado)

1. Las guardo como fixtures redactados en `fixtures/` (versionados).
2. Creo tests de **replay**: alimento el contenido real a la lógica de `config-backup`
   (versionado en Git, cálculo de `unsavedChanges`, `drift-check`) y verifico que **no se corrompe**,
   que los tamaños/caracteres reales se manejan, y que el diff/drift funciona con config real.
3. Te entrego un **reporte ejecutivo** por plataforma (✅/hallazgos) y actualizo la matriz de
   compatibilidad (Nivel 1).

> Cuando tengas al menos una captura lista (redactada), pásamela y ejecuto el Nivel 1.
