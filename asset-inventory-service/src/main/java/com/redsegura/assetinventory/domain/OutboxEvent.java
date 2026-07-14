package com.redsegura.assetinventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento pendiente de publicar (transactional outbox, RN11/ADR-04). Se inserta en la misma
 * transacción que la mutación del agregado; un relay lo publica a RabbitMQ y marca {@code
 * publishedAt}. El {@code payload} es el sobre (envelope) completo ya serializado a JSON.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "aggregate_type", nullable = false)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(nullable = false)
  private String payload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  protected OutboxEvent() {}

  public OutboxEvent(
      UUID eventId, String aggregateType, UUID aggregateId, String eventType, String payload) {
    this.eventId = eventId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payload = payload;
    this.createdAt = Instant.now();
  }

  /** Marca el evento como publicado (idempotente respecto al relay). */
  public void markPublished() {
    this.publishedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }
}
