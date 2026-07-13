package com.redsegura.assetinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Registro de una clave de idempotencia (RN9): asocia un {@code Idempotency-Key} al dispositivo que
 * creó, para que un reintento con la misma clave devuelva el recurso original sin duplicar.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

  @Id
  @Column(name = "id_key")
  private String idKey;

  @Column(name = "device_id", nullable = false)
  private UUID deviceId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected IdempotencyRecord() {}

  public IdempotencyRecord(String idKey, UUID deviceId) {
    this.idKey = idKey;
    this.deviceId = deviceId;
    this.createdAt = Instant.now();
  }

  public String getIdKey() {
    return idKey;
  }

  public UUID getDeviceId() {
    return deviceId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
