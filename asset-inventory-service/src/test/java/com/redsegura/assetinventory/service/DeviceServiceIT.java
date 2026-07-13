package com.redsegura.assetinventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.exception.DeviceDecommissionedException;
import com.redsegura.assetinventory.exception.DeviceNotFoundException;
import com.redsegura.assetinventory.exception.DuplicateDeviceException;
import com.redsegura.assetinventory.generated.model.Criticality;
import com.redsegura.assetinventory.generated.model.Device;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import com.redsegura.assetinventory.generated.model.DeviceStatus;
import com.redsegura.assetinventory.generated.model.DeviceType;
import com.redsegura.assetinventory.generated.model.DeviceUpdateRequest;
import com.redsegura.assetinventory.generated.model.Location;
import com.redsegura.assetinventory.repository.DeviceRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Reglas de negocio del inventario (RN1..RN7) contra PostgreSQL real, con los DTOs del contrato.
 */
class DeviceServiceIT extends AbstractIntegrationTest {

  @Autowired private DeviceService service;
  @Autowired private DeviceRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  private static DeviceCreateRequest req(String serial, String hostname, String ip) {
    return new DeviceCreateRequest(serial, hostname, ip, DeviceType.SWITCH, Criticality.ALTA)
        .assetTag("A-1")
        .vendor("Cisco")
        .model("C9300")
        .location(new Location().site("MX-DC1").room("Sala 2").row("B").rack("B07").rackUnit(12));
  }

  @Test
  void create_thenFindById() {
    Device created = service.create(req("S1", "SW1", "10.0.0.1"));

    assertThat(created.getId()).isNotNull();
    Device found = service.findById(created.getId());
    assertThat(found.getSerialNumber()).isEqualTo("S1");
    assertThat(found.getStatus()).isEqualTo(DeviceStatus.ACTIVO);
    assertThat(found.getCreatedBy()).isEqualTo("system");
    assertThat(found.getLocation().getSite()).isEqualTo("MX-DC1");
  }

  /** RN1: serialNumber duplicado -> 409. */
  @Test
  void create_duplicateSerial_throwsDuplicate() {
    service.create(req("DUP", "SW1", "10.0.0.1"));

    assertThatThrownBy(() -> service.create(req("DUP", "SW2", "10.0.0.2")))
        .isInstanceOf(DuplicateDeviceException.class);
  }

  @Test
  void findById_missing_throwsNotFound() {
    assertThatThrownBy(() -> service.findById(UUID.randomUUID()))
        .isInstanceOf(DeviceNotFoundException.class);
  }

  @Test
  void update_appliesOnlyProvidedFields() {
    Device c = service.create(req("S1", "SW1", "10.0.0.1"));

    Device updated =
        service.update(
            c.getId(),
            new DeviceUpdateRequest()
                .hostname("SW1-NEW")
                .deviceType(DeviceType.ROUTER)
                .criticality(Criticality.MEDIA));

    assertThat(updated.getHostname()).isEqualTo("SW1-NEW");
    assertThat(updated.getDeviceType()).isEqualTo(DeviceType.ROUTER);
    assertThat(updated.getCriticality()).isEqualTo(Criticality.MEDIA);
    assertThat(updated.getMgmtIp()).isEqualTo("10.0.0.1"); // no cambia
    assertThat(updated.getSerialNumber()).isEqualTo("S1"); // inmutable (RN2)
  }

  /** RN6: no se puede editar un dispositivo dado de baja. */
  @Test
  void update_decommissioned_throwsConflict() {
    Device c = service.create(req("S1", "SW1", "10.0.0.1"));
    service.decommission(c.getId());

    assertThatThrownBy(() -> service.update(c.getId(), new DeviceUpdateRequest().hostname("X")))
        .isInstanceOf(DeviceDecommissionedException.class);
  }

  /** RN6/FLOW-01: la baja es lógica y se excluye de los listados salvo filtro estado=BAJA. */
  @Test
  void decommission_isSoftAndExcludedFromDefaultSearch() {
    Device c = service.create(req("S1", "SW1", "10.0.0.1"));

    service.decommission(c.getId());

    assertThat(service.findById(c.getId()).getStatus()).isEqualTo(DeviceStatus.BAJA);
    assertThat(
            service
                .search(null, null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .getContent())
        .isEmpty();
    assertThat(
            service
                .search(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    com.redsegura.assetinventory.domain.DeviceStatus.BAJA,
                    PageRequest.of(0, 20))
                .getContent())
        .hasSize(1);
  }

  @Test
  void search_filtersByHostnameCaseInsensitive() {
    service.create(req("S1", "SW-CORE", "10.0.0.1"));
    service.create(req("S2", "RT-EDGE", "10.0.0.2"));

    var page =
        service.search("core", null, null, null, null, null, null, null, PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).getHostname()).isEqualTo("SW-CORE");
    assertThat(page.getTotalElements()).isEqualTo(1);
  }
}
