package com.redsegura.assetinventory.repository;

import com.redsegura.assetinventory.domain.ImportJob;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistencia de los jobs de importación masiva (RF-04). */
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

  Optional<ImportJob> findByIdempotencyKeyAndCreatedBy(String idempotencyKey, String createdBy);

  /**
   * Retención: borra los jobs anteriores al corte. Sus {@code import_job_results} se eliminan por
   * el {@code ON DELETE CASCADE} de la FK (migración V4).
   */
  @Modifying
  @Query("DELETE FROM ImportJob j WHERE j.createdAt < :cutoff")
  int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
