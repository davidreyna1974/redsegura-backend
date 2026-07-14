package com.redsegura.assetinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Job de importación masiva (RF-04). Registra el progreso agregado (total/succeeded/failed) y su
 * estado; el detalle por dispositivo vive en {@link ImportJobResult}. Idempotencia a nivel job:
 * acotada por {@code (idempotencyKey, createdBy)} y validada contra {@code requestHash} (RN9).
 */
@Entity
@Table(
    name = "import_jobs",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_import_job_key_user",
            columnNames = {"idempotency_key", "created_by"}))
public class ImportJob {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private JobStatus status;

  @Column(nullable = false)
  private int total;

  @Column(nullable = false)
  private int succeeded;

  @Column(nullable = false)
  private int failed;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  @Column(name = "created_by", nullable = false)
  private String createdBy;

  @Column(name = "request_hash", length = 64)
  private String requestHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ImportJob() {}

  public ImportJob(int total, String idempotencyKey, String createdBy, String requestHash) {
    this.status = JobStatus.QUEUED;
    this.total = total;
    this.succeeded = 0;
    this.failed = 0;
    this.idempotencyKey = idempotencyKey;
    this.createdBy = createdBy;
    this.requestHash = requestHash;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public void markInProgress() {
    this.status = JobStatus.IN_PROGRESS;
    touch();
  }

  public void markCompleted() {
    this.status = JobStatus.COMPLETED;
    touch();
  }

  public void markFailed() {
    this.status = JobStatus.FAILED;
    touch();
  }

  public void recordSucceeded() {
    this.succeeded++;
    touch();
  }

  public void recordFailed() {
    this.failed++;
    touch();
  }

  private void touch() {
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public JobStatus getStatus() {
    return status;
  }

  public int getTotal() {
    return total;
  }

  public int getSucceeded() {
    return succeeded;
  }

  public int getFailed() {
    return failed;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
