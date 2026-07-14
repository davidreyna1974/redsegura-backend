package com.redsegura.assetinventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.domain.ImportJob;
import com.redsegura.assetinventory.exception.IdempotencyKeyConflictException;
import com.redsegura.assetinventory.generated.model.BulkImportRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;

/**
 * Orquesta el alta de un job de importación masiva (RF-04): valida idempotencia a nivel job, crea
 * el job (QUEUED) y encola su procesamiento asíncrono. No es transaccional a propósito: el job debe
 * quedar confirmado antes de que el worker asíncrono intente leerlo.
 */
@Service
public class BulkImportService {

  private final JobService jobService;
  private final BulkImportProcessor processor;
  private final AuditorAware<String> auditorAware;
  private final ObjectMapper objectMapper;

  public BulkImportService(
      JobService jobService,
      BulkImportProcessor processor,
      AuditorAware<String> auditorAware,
      ObjectMapper objectMapper) {
    this.jobService = jobService;
    this.processor = processor;
    this.auditorAware = auditorAware;
    this.objectMapper = objectMapper;
  }

  /**
   * Crea (o reproduce, si el {@code Idempotency-Key} ya se usó) el job y encola su procesamiento.
   * Misma clave + cuerpo distinto → 409 (RN9), como en el alta individual.
   */
  public ImportJob submit(BulkImportRequest request, String idempotencyKey) {
    boolean hasKey = idempotencyKey != null && !idempotencyKey.isBlank();
    String requestHash = null;
    String currentUser = auditorAware.getCurrentAuditor().orElse("system");
    if (hasKey) {
      requestHash = hashOf(request);
      Optional<ImportJob> existing = jobService.findByKey(idempotencyKey, currentUser);
      if (existing.isPresent()) {
        if (!requestHash.equals(existing.get().getRequestHash())) {
          throw new IdempotencyKeyConflictException(
              "El Idempotency-Key ya se usó con un cuerpo distinto");
        }
        return existing.get();
      }
    }
    ImportJob job =
        jobService.createQueued(
            request.getDevices().size(), hasKey ? idempotencyKey : null, currentUser, requestHash);
    processor.enqueue(job.getId(), request.getDevices());
    return job;
  }

  private String hashOf(BulkImportRequest request) {
    try {
      byte[] canonical = objectMapper.writeValueAsBytes(request);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("No se pudo calcular el hash del lote", e);
    }
  }
}
