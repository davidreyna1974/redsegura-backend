package com.redsegura.assetinventory.service;

import com.redsegura.assetinventory.domain.ImportJob;
import com.redsegura.assetinventory.domain.ImportJobResult;
import com.redsegura.assetinventory.exception.JobNotFoundException;
import com.redsegura.assetinventory.repository.ImportJobRepository;
import com.redsegura.assetinventory.repository.ImportJobResultRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestión del ciclo de vida de un job de importación (RF-04). Cada operación va en su propia
 * transacción para que el resultado de un dispositivo no dependa del de otro (aislamiento por
 * ítem).
 */
@Service
public class JobService {

  /** Resultado por dispositivo: creado con éxito. */
  public static final String OUTCOME_CREATED = "CREATED";

  /** Resultado por dispositivo: falló (con detalle en {@code detail}). */
  public static final String OUTCOME_FAILED = "FAILED";

  private final ImportJobRepository jobRepository;
  private final ImportJobResultRepository resultRepository;

  public JobService(ImportJobRepository jobRepository, ImportJobResultRepository resultRepository) {
    this.jobRepository = jobRepository;
    this.resultRepository = resultRepository;
  }

  @Transactional
  public ImportJob createQueued(int total, String idempotencyKey, String createdBy, String hash) {
    return jobRepository.save(new ImportJob(total, idempotencyKey, createdBy, hash));
  }

  @Transactional(readOnly = true)
  public Optional<ImportJob> findByKey(String idempotencyKey, String createdBy) {
    return jobRepository.findByIdempotencyKeyAndCreatedBy(idempotencyKey, createdBy);
  }

  @Transactional
  public void markInProgress(UUID jobId) {
    getOrThrow(jobId).markInProgress();
  }

  @Transactional
  public void markCompleted(UUID jobId) {
    getOrThrow(jobId).markCompleted();
  }

  @Transactional
  public void markFailed(UUID jobId) {
    getOrThrow(jobId).markFailed();
  }

  /**
   * Registra el resultado de un dispositivo y actualiza los contadores del job (transacción
   * propia).
   */
  @Transactional
  public void recordResult(UUID jobId, String serialNumber, String outcome, String detail) {
    ImportJob job = getOrThrow(jobId);
    resultRepository.save(new ImportJobResult(jobId, serialNumber, outcome, detail));
    if (OUTCOME_CREATED.equals(outcome)) {
      job.recordSucceeded();
    } else {
      job.recordFailed();
    }
  }

  @Transactional(readOnly = true)
  public ImportJob getJob(UUID jobId) {
    return getOrThrow(jobId);
  }

  @Transactional(readOnly = true)
  public List<ImportJobResult> getResults(UUID jobId) {
    return resultRepository.findByJobIdOrderByCreatedAtAsc(jobId);
  }

  private ImportJob getOrThrow(UUID jobId) {
    return jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
  }
}
