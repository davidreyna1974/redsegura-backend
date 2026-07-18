package com.redsegura.assetinventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.domain.OutboxEvent;
import com.redsegura.assetinventory.repository.OutboxRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RNF-30/ADR-15 — comportamiento concurrente del relay del outbox (`FOR UPDATE SKIP LOCKED`).
 * Prueba, de forma <b>determinista</b> (sincronización por {@link CountDownLatch}, sin {@code
 * Thread.sleep}), que con dos réplicas drenando el mismo outbox a la vez, la segunda transacción
 * <b>salta</b> las filas que la primera tiene bloqueadas (no se bloquea ni las vuelve a tomar) →
 * sin publicación duplicada ni contención. Plan y criterios: {@code
 * documentos/plan_test_concurrencia_outbox.md}.
 *
 * <p><b>Diente:</b> si se quitara {@code SKIP LOCKED} de la query, T2 se bloquearía en el lock de
 * T1; con {@code lock_timeout} corto, la query de T2 fallaría y el test también → detecta la
 * regresión.
 */
class OutboxConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private OutboxRepository outboxRepository;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private ExecutorService pool;

  @BeforeEach
  void setup() {
    outboxRepository.deleteAll();
    pool = Executors.newFixedThreadPool(2);
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  @Test
  void secondTransaction_skipsRowsLockedByFirst() throws Exception {
    // Dos eventos pendientes, ya committeados (visibles para ambas transacciones).
    outboxRepository.save(newEvent("asset.created"));
    outboxRepository.save(newEvent("asset.updated"));

    CountDownLatch t1HasLock = new CountDownLatch(1);
    CountDownLatch t2HasQueried = new CountDownLatch(1);
    TransactionTemplate tx = new TransactionTemplate(txManager);

    // T1: bloquea el lote y mantiene su transacción ABIERTA hasta que T2 haya consultado.
    Future<?> t1 =
        pool.submit(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      List<OutboxEvent> batch = outboxRepository.lockPendingBatch();
                      assertThat(batch).as("T1 toma el lote completo").hasSize(2);
                      t1HasLock.countDown();
                      awaitOrFail(t2HasQueried, 8);
                    }));

    // T2: tras confirmar que T1 tiene el lock, consulta su propio lote (debe venir vacío).
    Future<List<OutboxEvent>> t2 =
        pool.submit(
            () -> {
              awaitOrFail(t1HasLock, 8);
              try {
                return tx.execute(
                    status -> {
                      // Si SKIP LOCKED no estuviera, esta query se bloquearía; el lock_timeout la
                      // haría fallar en ~2 s (da dientes al test).
                      jdbcTemplate.execute("SET LOCAL lock_timeout = '2000ms'");
                      return outboxRepository.lockPendingBatch();
                    });
              } finally {
                t2HasQueried.countDown();
              }
            });

    List<OutboxEvent> t2Batch = t2.get(20, TimeUnit.SECONDS);
    t1.get(20, TimeUnit.SECONDS);

    assertThat(t2Batch)
        .as(
            "la 2.ª transacción salta las filas bloqueadas por la 1.ª (SKIP LOCKED), no las re-toma")
        .isEmpty();
  }

  private static OutboxEvent newEvent(String eventType) {
    return new OutboxEvent(UUID.randomUUID(), "Device", UUID.randomUUID(), eventType, "{}");
  }

  private static void awaitOrFail(CountDownLatch latch, long seconds) {
    try {
      if (!latch.await(seconds, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
            "timeout esperando la sincronización del test de concurrencia");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
