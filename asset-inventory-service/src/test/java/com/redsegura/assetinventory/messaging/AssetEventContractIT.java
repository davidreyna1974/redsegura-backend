package com.redsegura.assetinventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.domain.OutboxEvent;
import com.redsegura.assetinventory.exception.DuplicateDeviceException;
import com.redsegura.assetinventory.generated.model.Criticality;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import com.redsegura.assetinventory.generated.model.DeviceType;
import com.redsegura.assetinventory.generated.model.DeviceUpdateRequest;
import com.redsegura.assetinventory.generated.model.Ipv4Address;
import com.redsegura.assetinventory.generated.model.Ipv6Address;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.OutboxRepository;
import com.redsegura.assetinventory.service.DeviceService;
import com.redsegura.assetinventory.service.VersionedDevice;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Conformidad de contrato de eventos (productor-side): los eventos {@code asset.*} emitidos al
 * outbox validan contra su JSON Schema formal ({@code /contracts/asset-event.schema.json}). Cubre
 * los tres caminos:
 *
 * <ul>
 *   <li><b>happy:</b> create/update/decommission producen eventos válidos;
 *   <li><b>edge:</b> solo-IPv6 y dual-stack validan (familias opcionales);
 *   <li><b>sad:</b> (1) una operación fallida NO emite evento; (2) un payload malformado es
 *       rechazado por el schema (el schema "tiene dientes").
 * </ul>
 */
class AssetEventContractIT extends AbstractIntegrationTest {

  private static final JsonSchema SCHEMA =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
          .getSchema(
              AssetEventContractIT.class.getResourceAsStream("/events/asset-event.schema.json"));

  @Autowired private DeviceService service;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private DeviceRepository deviceRepository;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    outboxRepository.deleteAll();
    deviceRepository.deleteAll();
  }

  private static DeviceCreateRequest ipv4(String serial, String hostname, String ip) {
    return new DeviceCreateRequest(serial, hostname, DeviceType.SWITCH, Criticality.ALTA)
        .managementIpv4(new Ipv4Address().address(ip).prefixLength(24).gateway("10.0.0.254"))
        .vendor("Cisco")
        .model("C9300");
  }

  private JsonNode onlyEnvelope() throws Exception {
    OutboxEvent event = outboxRepository.findAll().get(0);
    return objectMapper.readTree(event.getPayload());
  }

  private Set<ValidationMessage> validate(JsonNode envelope) {
    return SCHEMA.validate(envelope);
  }

  // ---------- happy ----------
  @Test
  void assetCreated_conformsToSchema() throws Exception {
    service.create(ipv4("S1", "SW1", "10.0.0.1"), null);

    JsonNode envelope = onlyEnvelope();
    assertThat(validate(envelope)).isEmpty();
    assertThat(envelope.get("eventType").asText()).isEqualTo("asset.created");
    assertThat(envelope.get("version").asText()).isEqualTo("1.1.0");
  }

  @Test
  void assetUpdated_conformsToSchema() throws Exception {
    VersionedDevice c = service.create(ipv4("S1", "SW1", "10.0.0.1"), null);
    outboxRepository.deleteAll();

    service.update(
        c.body().getId(), c.version(), new DeviceUpdateRequest().criticality(Criticality.MEDIA));

    JsonNode envelope = onlyEnvelope();
    assertThat(validate(envelope)).isEmpty();
    assertThat(envelope.get("eventType").asText()).isEqualTo("asset.updated");
    assertThat(envelope.get("payload").get("changedFields")).isNotNull();
  }

  @Test
  void assetDecommissioned_conformsToSchema() throws Exception {
    VersionedDevice c = service.create(ipv4("S1", "SW1", "10.0.0.1"), null);
    outboxRepository.deleteAll();

    service.decommission(c.body().getId(), c.version());

    JsonNode envelope = onlyEnvelope();
    assertThat(validate(envelope)).isEmpty();
    assertThat(envelope.get("payload").get("status").asText()).isEqualTo("BAJA");
  }

  // ---------- edge ----------
  @Test
  void assetCreated_onlyIpv6_conformsToSchema() throws Exception {
    service.create(
        new DeviceCreateRequest("S1", "SW1", DeviceType.ROUTER, Criticality.MEDIA)
            .managementIpv6(new Ipv6Address().address("2001:db8::1").prefixLength(64)),
        null);

    JsonNode envelope = onlyEnvelope();
    assertThat(validate(envelope)).isEmpty();
    assertThat(envelope.get("payload").hasNonNull("managementIpv6")).isTrue();
    assertThat(envelope.get("payload").has("managementIpv4")).isFalse();
  }

  @Test
  void assetCreated_dualStack_conformsToSchema() throws Exception {
    service.create(
        new DeviceCreateRequest("S1", "SW1", DeviceType.SWITCH, Criticality.ALTA)
            .managementIpv4(new Ipv4Address().address("10.0.0.1").prefixLength(24))
            .managementIpv6(new Ipv6Address().address("2001:db8::1").prefixLength(64)),
        null);

    JsonNode envelope = onlyEnvelope();
    assertThat(validate(envelope)).isEmpty();
    assertThat(envelope.get("payload").hasNonNull("managementIpv4")).isTrue();
    assertThat(envelope.get("payload").hasNonNull("managementIpv6")).isTrue();
  }

  // ---------- sad ----------
  /** Invariante: una operación de negocio fallida NO emite evento (outbox transaccional). */
  @Test
  void failedMutation_emitsNoEvent() {
    service.create(ipv4("S1", "SW1", "10.0.0.1"), null); // 1 evento (asset.created)

    assertThatThrownBy(() -> service.create(ipv4("S1", "SW2", "10.0.0.2"), null))
        .isInstanceOf(DuplicateDeviceException.class);

    assertThat(outboxRepository.count()).isEqualTo(1); // el fallido no añadió evento
  }

  /** El schema rechaza un payload malformado (falta `deviceId`): prueba que tiene dientes. */
  @Test
  void malformedEvent_isRejectedBySchema() throws Exception {
    String malformed =
        """
        {"eventId":"e1","eventType":"asset.created","version":"1.1.0","occurredAt":"2026-07-14T00:00:00Z",\
        "source":"asset-inventory-service","payload":{"hostname":"SW1","criticality":"ALTA",\
        "status":"ACTIVO","managementIpv4":{"address":"10.0.0.1","prefixLength":24}}}""";

    Set<ValidationMessage> errors = validate(objectMapper.readTree(malformed));

    assertThat(errors).isNotEmpty(); // falta deviceId (requerido)
  }
}
