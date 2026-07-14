package com.redsegura.assetinventory.repository;

import com.redsegura.assetinventory.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistencia del outbox (RN11/ADR-04). */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

  /** Eventos aún no publicados, en orden de creación (los que consume el relay). */
  List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
