package com.redsegura.assetinventory.repository;

import com.redsegura.assetinventory.domain.ImportJob;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistencia de los jobs de importación masiva (RF-04). */
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

  Optional<ImportJob> findByIdempotencyKeyAndCreatedBy(String idempotencyKey, String createdBy);
}
