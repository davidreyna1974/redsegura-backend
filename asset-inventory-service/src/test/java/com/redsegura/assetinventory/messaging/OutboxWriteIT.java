package com.redsegura.assetinventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.domain.OutboxEvent;
import com.redsegura.assetinventory.generated.model.Criticality;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import com.redsegura.assetinventory.generated.model.DeviceType;
import com.redsegura.assetinventory.generated.model.DeviceUpdateRequest;
import com.redsegura.assetinventory.generated.model.Ipv4Address;
import com.redsegura.assetinventory.generated.model.Location;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.IdempotencyRepository;
import com.redsegura.assetinventory.repository.OutboxRepository;
import com.redsegura.assetinventory.service.DeviceService;
import com.redsegura.assetinventory.service.VersionedDevice;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Transactional outbox (RN11/ADR-04): cada mutación escribe el evento asset.* correcto en la misma
 * transacción. Solo requiere PostgreSQL (la publicación a RabbitMQ se prueba en {@link
 * OutboxRelayIT}).
 */
class OutboxWriteIT extends AbstractIntegrationTest {

  @Autowired private DeviceService service;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private DeviceRepository deviceRepository;
  @Autowired private IdempotencyRepository idempotencyRepository;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    outboxRepository.deleteAll();
    idempotencyRepository.deleteAll();
    deviceRepository.deleteAll();
  }

  private static DeviceCreateRequest req(String serial, String hostname, String ip) {
    return new DeviceCreateRequest(serial, hostname, DeviceType.SWITCH, Criticality.ALTA)
        .managementIpv4(new Ipv4Address().address(ip).prefixLength(24).gateway("10.0.0.254"))
        .vendor("Cisco")
        .model("C9300")
        .location(new Location().site("MX-DC1").rack("B07").rackUnit(12));
  }

  private JsonNode payloadOf(OutboxEvent event) throws Exception {
    return objectMapper.readTree(event.getPayload()).get("payload");
  }

  private JsonNode envelopeOf(OutboxEvent event) throws Exception {
    return objectMapper.readTree(event.getPayload());
  }

  /** RN11: el alta escribe asset.created con el sobre completo y el payload del contrato. */
  @Test
  void create_writesAssetCreatedEvent() throws Exception {
    VersionedDevice created = service.create(req("S1", "SW1", "10.0.0.1"), null);

    List<OutboxEvent> events = outboxRepository.findAll();
    assertThat(events).hasSize(1);
    OutboxEvent event = events.get(0);
    assertThat(event.getEventType()).isEqualTo("asset.created");
    assertThat(event.getAggregateId()).isEqualTo(created.body().getId());
    assertThat(event.getPublishedAt()).isNull();

    JsonNode envelope = envelopeOf(event);
    assertThat(envelope.get("eventType").asText()).isEqualTo("asset.created");
    assertThat(envelope.get("version").asText()).isEqualTo("1.1.0");
    assertThat(envelope.get("source").asText()).isEqualTo("asset-inventory-service");
    assertThat(envelope.hasNonNull("eventId")).isTrue();

    JsonNode payload = payloadOf(event);
    assertThat(payload.get("deviceId").asText()).isEqualTo(created.body().getId().toString());
    assertThat(payload.get("hostname").asText()).isEqualTo("SW1");
    assertThat(payload.get("managementIpv4").get("address").asText()).isEqualTo("10.0.0.1");
    assertThat(payload.get("managementIpv4").get("prefixLength").asInt()).isEqualTo(24);
    assertThat(payload.get("status").asText()).isEqualTo("ACTIVO");
    assertThat(payload.get("location").asText()).isEqualTo("MX-DC1 / B07 / U12");
  }

  /** RN11: la edición escribe asset.updated con changedFields. */
  @Test
  void update_writesAssetUpdatedEventWithChangedFields() throws Exception {
    VersionedDevice created = service.create(req("S1", "SW1", "10.0.0.1"), null);
    outboxRepository.deleteAll(); // aísla el evento de la edición

    service.update(
        created.body().getId(),
        created.version(),
        new DeviceUpdateRequest().hostname("SW1-NEW").criticality(Criticality.MEDIA));

    List<OutboxEvent> events = outboxRepository.findAll();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getEventType()).isEqualTo("asset.updated");

    JsonNode payload = payloadOf(events.get(0));
    assertThat(payload.get("hostname").asText()).isEqualTo("SW1-NEW");
    List<String> changed = objectMapper.convertValue(payload.get("changedFields"), List.class);
    assertThat(changed).containsExactlyInAnyOrder("hostname", "criticality");
  }

  /** RN11: la baja escribe asset.decommissioned con status BAJA. */
  @Test
  void decommission_writesAssetDecommissionedEvent() throws Exception {
    VersionedDevice created = service.create(req("S1", "SW1", "10.0.0.1"), null);
    outboxRepository.deleteAll();

    service.decommission(created.body().getId(), created.version());

    List<OutboxEvent> events = outboxRepository.findAll();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getEventType()).isEqualTo("asset.decommissioned");
    assertThat(payloadOf(events.get(0)).get("status").asText()).isEqualTo("BAJA");
  }
}
