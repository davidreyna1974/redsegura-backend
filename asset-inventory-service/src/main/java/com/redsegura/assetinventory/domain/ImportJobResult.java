package com.redsegura.assetinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Resultado de un dispositivo dentro de un job de importación masiva (contrato §Job.results). */
@Entity
@Table(name = "import_job_results")
public class ImportJobResult {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Column(name = "serial_number", nullable = false, length = 100)
  private String serialNumber;

  @Column(nullable = false, length = 20)
  private String outcome;

  @Column(length = 500)
  private String detail;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ImportJobResult() {}

  public ImportJobResult(UUID jobId, String serialNumber, String outcome, String detail) {
    this.jobId = jobId;
    this.serialNumber = serialNumber;
    this.outcome = outcome;
    this.detail = detail;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getJobId() {
    return jobId;
  }

  public String getSerialNumber() {
    return serialNumber;
  }

  public String getOutcome() {
    return outcome;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
