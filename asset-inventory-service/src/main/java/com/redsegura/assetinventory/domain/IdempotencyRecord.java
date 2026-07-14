package com.redsegura.assetinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Registro de una clave de idempotencia (RN9). Asocia un {@code Idempotency-Key} —acotado por el
 * usuario que lo emitió ({@code createdBy})— al dispositivo que creó y al hash del cuerpo original
 * ({@code requestHash}). Permite que un reintento idéntico devuelva el recurso original y que un
 * reuso de la clave con otro cuerpo se rechace, sin que dos usuarios distintos colisionen.
 */
@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_idempotency_key_user",
            columnNames = {"id_key", "created_by"}))
public class IdempotencyRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "id_key", nullable = false)
  private String idKey;

  @Column(name = "created_by", nullable = false)
  private String createdBy;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Column(name = "device_id", nullable = false)
  private UUID deviceId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected IdempotencyRecord() {}

  public IdempotencyRecord(String idKey, String createdBy, String requestHash, UUID deviceId) {
    this.idKey = idKey;
    this.createdBy = createdBy;
    this.requestHash = requestHash;
    this.deviceId = deviceId;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getIdKey() {
    return idKey;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public UUID getDeviceId() {
    return deviceId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
