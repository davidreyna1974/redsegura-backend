package com.redsegura.assetinventory.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.domain.OutboxEvent;
import com.redsegura.assetinventory.generated.model.Device;
import com.redsegura.assetinventory.generated.model.Location;
import com.redsegura.assetinventory.repository.OutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Construye el sobre (envelope) de los eventos {@code asset.*} (catálogo §3.3/§4.1) y lo persiste
 * en el outbox dentro de la transacción de negocio (RN11/ADR-04). No publica: de eso se encarga el
 * {@link OutboxRelay}. El {@code traceId} se toma del MDC (propagado por Micrometer Tracing,
 * RNF-16).
 */
@Component
public class OutboxWriter {

  private static final String AGGREGATE_TYPE = "Device";
  private static final String SOURCE = "asset-inventory-service";
  private static final String SCHEMA_VERSION = "1.0.0";

  private final OutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;

  public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * Registra un evento {@code asset.*} para el dispositivo dado. {@code changedFields} solo aplica
   * a {@code asset.updated}; para el resto se pasa {@code null}.
   */
  public void record(String eventType, Device device, List<String> changedFields) {
    UUID eventId = UUID.randomUUID();
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", eventType);
    envelope.put("version", SCHEMA_VERSION);
    envelope.put("occurredAt", Instant.now().toString());
    envelope.put("traceId", MDC.get("traceId"));
    envelope.put("source", SOURCE);
    envelope.put("payload", payloadOf(device, changedFields));

    outboxRepository.save(
        new OutboxEvent(eventId, AGGREGATE_TYPE, device.getId(), eventType, serialize(envelope)));
  }

  private static Map<String, Object> payloadOf(Device device, List<String> changedFields) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("deviceId", device.getId().toString());
    payload.put("hostname", device.getHostname());
    payload.put("mgmtIp", device.getMgmtIp());
    payload.put("vendor", device.getVendor());
    payload.put("model", device.getModel());
    payload.put("location", formatLocation(device.getLocation()));
    payload.put(
        "criticality", device.getCriticality() == null ? null : device.getCriticality().getValue());
    payload.put("status", device.getStatus() == null ? null : device.getStatus().getValue());
    if (changedFields != null) {
      payload.put("changedFields", changedFields);
    }
    return payload;
  }

  /**
   * Une las partes no vacías de la ubicación en una cadena legible (p. ej. "MX-DC1 / B07 / U12").
   */
  private static String formatLocation(Location location) {
    if (location == null) {
      return null;
    }
    List<String> parts =
        Stream.of(
                location.getSite(),
                location.getRoom(),
                location.getRow(),
                location.getRack(),
                location.getRackUnit() == null ? null : "U" + location.getRackUnit())
            .filter(p -> p != null && !p.isBlank())
            .collect(Collectors.toCollection(ArrayList::new));
    return parts.isEmpty() ? null : String.join(" / ", parts);
  }

  private String serialize(Map<String, Object> envelope) {
    try {
      return objectMapper.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("No se pudo serializar el evento de outbox", e);
    }
  }
}
