# Plan de prueba — concurrencia del relay del outbox (`SKIP LOCKED`)

> **Objetivo del documento:** planificar, de forma informada y justificada, el test automatizado
> **determinista** que prueba la propiedad de concurrencia del relay del outbox (Opción A del análisis
> previo). No es el reporte de resultados; es el diseño y los criterios con los que se aceptará.

**Servicio:** asset-inventory-service · **Fecha:** 2026-07-17 · **Estado:** ✅ **EJECUTADO**
(`OutboxConcurrencyIT`, 2026-07-17) · **Autor:** equipo de desarrollo

> **Resultado:** implementado según §4. **Todos los criterios de éxito (§5) cumplidos:** correcto;
> determinista (latches, sin `sleep`); **50 corridas consecutivas en verde, 0 flaky** (7,1 s);
> **"diente" confirmado** (quitar `SKIP LOCKED` → T2 se bloquea → `lock timeout` → el test falla);
> aislado y < 10 s; gatekeeper en verde con **suite → 110 tests**; `EVT-05` y `matriz_rnf.md`
> actualizados. No se necesitó el plan B (opción C+D).

---

## 1. Contexto y requisito que cubre

- **Requisito:** **RNF-30** (entrega garantizada de eventos) — cláusula "relay **seguro ante múltiples
  réplicas**" — materializado en **ADR-15**; habilitado por **RNF-13** (servicio *stateless*,
  escalable horizontalmente).
- **Implementación bajo prueba:** `OutboxRepository.lockPendingBatch()` — query nativa
  `SELECT … FOR UPDATE SKIP LOCKED LIMIT 100` — invocada dentro de `OutboxRelay.publishPending()`
  (`@Transactional`), disparada por `OutboxRelayScheduler` (`@Scheduled`, cada 2 s) en **cada réplica**.
- **Qué garantiza:** con N réplicas drenando el mismo outbox a la vez, cada evento lo toma **una sola
  réplica por ciclo** → sin publicación duplicada ni contención de locks.

## 2. Hueco actual (por qué hace falta este test)

| Caso existente | Qué cubre | Qué NO cubre |
|---|---|---|
| `OutboxRelayIT` (EVT-04) | Publica + confirma + marca publicado (un hilo, broker real) | El comportamiento **concurrente** |
| `OutboxRelayConfirmFailureTest` (EVT-06) | Sin ACK → no marca publicado | " |
| Caso `EVT-05` (matriz) | La query lleva `SKIP LOCKED` (presencia) | Que **funcione** bajo contención real |

**Con un solo hilo, `SKIP LOCKED` es indistinguible de no tenerlo** (devuelve lo mismo). La propiedad
solo se observa con **dos transacciones solapadas**. Ese es el hueco que este test cierra.

## 3. Justificación de la Opción A (frente a B/C/D)

Del análisis previo (opciones B: dos relays en paralelo; C: verificar presencia de la cláusula;
D: verificación de diseño documentada):

- **C es insuficiente** para el mandato productivo: prueba presencia, no comportamiento.
- **D es lo que hace gran parte de la industria** (se confía en la semántica de Postgres, muy probada),
  pero deja la propiedad sin red automatizada.
- **B es no determinista** (depende de timing), tendería a *flaky*.
- **A** es el estándar de oro: prueba el **comportamiento real** de forma **determinista** (con
  sincronización explícita, no con `sleep`), y se ejecuta **una vez** para quedar blindada. Coste alto
  pero acotado; alineado con "capacidad productiva real". **Se elige A.**

## 4. Diseño del test (Opción A)

**Idea:** forzar una carrera controlada entre dos transacciones sobre el mismo lote de outbox y
comprobar que la segunda **salta** las filas que la primera tiene bloqueadas.

**Prerrequisitos técnicos:**
- **PostgreSQL real** (Testcontainers) — `SKIP LOCKED` es del motor; H2 no sirve. Se reutiliza
  `AbstractIntegrationTest`.
- **Gestión manual de transacciones en dos hilos** — no se puede usar el `@Transactional` de test (un
  hilo, una transacción). Se usará `TransactionTemplate`/`PlatformTransactionManager` + un
  `ExecutorService` de 2 hilos, y sincronización con `CountDownLatch`.

**Secuencia determinista (sin `Thread.sleep`):**
```
seed: insertar 2 eventos outbox (published_at = NULL)

Hilo T1 (transacción propia):
  1. abrir tx
  2. lockPendingBatch()  → bloquea las 2 filas (FOR UPDATE)
  3. latchLocked.countDown()      // avisa "ya bloqueé"
  4. esperar latchRelease (bloquea aquí, tx ABIERTA, sin commit)
  5. (al liberarse) commit + fin

Hilo T2 (transacción propia):
  1. latchLocked.await(timeout)   // espera a que T1 tenga el lock
  2. abrir tx
  3. resultado = lockPendingBatch()
  4. latchRelease.countDown()     // libera a T1
  5. commit

Aserciones (en el hilo principal, tras join):
  - resultado de T2 es VACÍO  (saltó las filas bloqueadas por T1)  ← prueba SKIP LOCKED
  - T2 respondió dentro de un límite corto (no se quedó bloqueado)
```

**Por qué es determinista:** el orden de los pasos lo imponen los `CountDownLatch`, no el reloj. T2
solo consulta **después** de que T1 confirmó el bloqueo; T1 solo commitea **después** de que T2 ya
consultó.

**Diente (mutation-sensitivity):** si alguien quita `SKIP LOCKED` (dejando solo `FOR UPDATE`), la
query de T2 **se bloquearía** esperando el lock de T1 en vez de saltarlo → T2 no respondería dentro
del límite corto y el test **fallaría**. Es decir, el test **detecta la regresión**, no es decorativo.

## 5. Criterios de éxito (definidos)

El test se acepta si cumple **todos**:

1. **Correctitud:** demuestra que T2 obtiene un resultado **vacío/disjunto** mientras T1 mantiene el
   lote bloqueado (comportamiento `SKIP LOCKED`).
2. **Determinismo:** la sincronización es por `CountDownLatch`/barreras; **cero `Thread.sleep`** para
   ordenar los pasos (solo timeouts de seguridad).
3. **No *flaky*:** **50 ejecuciones locales consecutivas en verde, 0 fallos** (`mvn -pl asset-inventory-service test -Dtest=OutboxConcurrencyIT` en bucle) y **verde en CI**.
4. **Con dientes:** si se elimina `SKIP LOCKED` de la query, el test **falla** (verificado
   manualmente una vez, quitando la cláusula en local).
5. **Aislado y limpio:** no deja datos residuales; no agota el *connection pool* (cierra ambas
   conexiones/transacciones aunque falle); tiempo de ejecución < 10 s.
6. **Sin degradar el gate:** el gatekeeper completo sigue en verde (cobertura ≥ 70 %, 0 lint) y la
   suite total incrementa en 1 test (→ 110).
7. **Trazabilidad:** `EVT-05` en `casos_de_prueba.md` pasa de "presencia de la cláusula" a
   "comportamiento concurrente verificado"; matriz de RNF actualizada con la evidencia.

## 6. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| *Flakiness* por timing | Sincronización por latches, no por `sleep`; timeouts amplios (p. ej. 5 s) solo como red de seguridad |
| *Deadlock* del propio test (T1 espera a T2 y viceversa) | Orden estricto de latches + `await(timeout)`; si expira, el test falla rápido en vez de colgarse |
| Fuga de conexiones/tx abiertas | `try/finally` que hace rollback y cierra ambas tx; `ExecutorService` con `shutdownNow` en `@AfterEach` |
| Timeout de lock de Postgres enmascarando el caso | Fijar `lock_timeout`/`statement_timeout` corto en las conexiones del test para que el "bloqueo" (caso sin SKIP LOCKED) se manifieste como fallo claro |
| CI más lento | El test es < 10 s y reutiliza el contenedor Postgres ya levantado por la suite |

## 7. Alcance y esfuerzo

- **Un archivo nuevo:** `OutboxConcurrencyIT.java` (integración, Testcontainers), ~70–100 líneas.
- **Sin cambios de producción** (solo test); blast radius **local**.
- **Esfuerzo estimado:** 0.5–1 día (la lógica es corta pero el blindaje anti-flaky y la verificación
  de las 50 corridas llevan tiempo).

## 8. Criterio de aborto (plan B)

Si tras un esfuerzo acotado el test resulta **irremediablemente flaky** en CI (falla las 50 corridas o
en el runner), se **degrada a Opción C + D**: test barato que confirma la presencia de `SKIP LOCKED`
+ nota de diseño documentada que se apoya en la garantía del motor. Se registra la decisión en la
memoria técnica y en `preparacion_produccion.md`. **No se mergea un test flaky** (contamina el gate).

## 9. Definición de "done" de este plan

```
[x] OutboxConcurrencyIT implementado según §4, determinista (§5.2).
[x] 50 corridas locales en verde (§5.3) + verde en CI.
[x] Verificado el "diente": quitar SKIP LOCKED hace fallar el test (§5.4).
[x] Gatekeeper completo en verde; suite → 110 tests (§5.6).
[x] casos_de_prueba.md (EVT-05) y matriz_rnf.md actualizados (§5.7).
[x] memoria_tecnica.md registra el cierre de la verificación de concurrencia de RNF-30.
```

## 10. Referencias
- Requisito: `../../../management/documentos/proyecto_microservicios_redsegura.md §8` (RNF-30, RNF-13).
- Decisión: `../../../management/documentos/arquitectura/memoria_tecnica_global.md` (ADR-15).
- Código: `OutboxRepository.lockPendingBatch()`, `OutboxRelay.publishPending()`.
- Casos: [`casos_de_prueba.md`](casos_de_prueba.md) (EVT-05), [`matriz_rnf.md`](matriz_rnf.md) (RNF-30).
