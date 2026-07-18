package com.redsegura.assetinventory.repository;

import com.redsegura.assetinventory.domain.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistencia de las claves de idempotencia (RN9), acotadas por (clave, usuario). */
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {

  Optional<IdempotencyRecord> findByIdKeyAndCreatedBy(String idKey, String createdBy);

  /** Retención: borra las claves anteriores al corte (ya fuera de la ventana de idempotencia). */
  @Modifying
  @Query("DELETE FROM IdempotencyRecord i WHERE i.createdAt < :cutoff")
  int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
