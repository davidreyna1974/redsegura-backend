package com.redsegura.assetinventory.repository;

import com.redsegura.assetinventory.domain.ImportJobResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistencia del resultado por dispositivo de un job de importación (RF-04). */
public interface ImportJobResultRepository extends JpaRepository<ImportJobResult, UUID> {

  List<ImportJobResult> findByJobIdOrderByCreatedAtAsc(UUID jobId);
}
