package com.redsegura.assetinventory.repository;

import com.redsegura.assetinventory.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistencia del outbox (RN11/ADR-04). */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

  /**
   * Lote de eventos pendientes bloqueado para este relay (RNF-30/ADR-15). {@code FOR UPDATE SKIP
   * LOCKED} hace que, con varias réplicas del servicio corriendo, cada una tome un lote distinto
   * sin bloquearse ni publicar los mismos eventos: las filas ya tomadas por otra transacción se
   * saltan. Debe ejecutarse dentro de una transacción (el relay lo es).
   */
  @Query(
      value =
          """
          SELECT * FROM outbox_events
          WHERE published_at IS NULL
          ORDER BY created_at ASC
          LIMIT 100
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<OutboxEvent> lockPendingBatch();
}
