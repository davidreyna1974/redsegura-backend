package com.redsegura.assetinventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.DeviceStatus;
import com.redsegura.assetinventory.domain.DeviceType;
import com.redsegura.assetinventory.exception.DeviceDecommissionedException;
import com.redsegura.assetinventory.exception.DeviceNotFoundException;
import com.redsegura.assetinventory.exception.DuplicateDeviceException;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.web.dto.DeviceCreateRequest;
import com.redsegura.assetinventory.web.dto.DeviceResponse;
import com.redsegura.assetinventory.web.dto.DeviceUpdateRequest;
import com.redsegura.assetinventory.web.dto.LocationDto;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** Reglas de negocio del inventario (RN1..RN7) contra PostgreSQL real. */
class DeviceServiceIT extends AbstractIntegrationTest {

  @Autowired private DeviceService service;
  @Autowired private DeviceRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  private static DeviceCreateRequest req(String serial, String hostname, String ip) {
    return new DeviceCreateRequest(
        serial,
        "A-1",
        hostname,
        ip,
        DeviceType.SWITCH,
        "Cisco",
        "C9300",
        new LocationDto("MX-DC1", "Sala 2", "B", "B07", 12),
        Criticality.ALTA);
  }

  @Test
  void create_thenFindById() {
    DeviceResponse created = service.create(req("S1", "SW1", "10.0.0.1"));

    assertThat(created.id()).isNotNull();
    DeviceResponse found = service.findById(created.id());
    assertThat(found.serialNumber()).isEqualTo("S1");
    assertThat(found.status()).isEqualTo(DeviceStatus.ACTIVO);
    assertThat(found.createdBy()).isEqualTo("system");
    assertThat(found.location().site()).isEqualTo("MX-DC1");
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
    DeviceResponse c = service.create(req("S1", "SW1", "10.0.0.1"));

    DeviceResponse updated =
        service.update(
            c.id(),
            new DeviceUpdateRequest(
                null, "SW1-NEW", null, DeviceType.ROUTER, null, null, null, Criticality.MEDIA));

    assertThat(updated.hostname()).isEqualTo("SW1-NEW");
    assertThat(updated.deviceType()).isEqualTo(DeviceType.ROUTER);
    assertThat(updated.criticality()).isEqualTo(Criticality.MEDIA);
    assertThat(updated.mgmtIp()).isEqualTo("10.0.0.1"); // no cambia
    assertThat(updated.serialNumber()).isEqualTo("S1"); // inmutable (RN2)
  }

  /** RN6: no se puede editar un dispositivo dado de baja. */
  @Test
  void update_decommissioned_throwsConflict() {
    DeviceResponse c = service.create(req("S1", "SW1", "10.0.0.1"));
    service.decommission(c.id());

    assertThatThrownBy(
            () ->
                service.update(
                    c.id(), new DeviceUpdateRequest(null, "X", null, null, null, null, null, null)))
        .isInstanceOf(DeviceDecommissionedException.class);
  }

  /** RN6/FLOW-01: la baja es lógica y se excluye de los listados salvo filtro estado=BAJA. */
  @Test
  void decommission_isSoftAndExcludedFromDefaultSearch() {
    DeviceResponse c = service.create(req("S1", "SW1", "10.0.0.1"));

    service.decommission(c.id());

    assertThat(service.findById(c.id()).status()).isEqualTo(DeviceStatus.BAJA);
    assertThat(
            service
                .search(null, null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .content())
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
                    DeviceStatus.BAJA,
                    PageRequest.of(0, 20))
                .content())
        .hasSize(1);
  }

  @Test
  void search_filtersByHostnameCaseInsensitive() {
    service.create(req("S1", "SW-CORE", "10.0.0.1"));
    service.create(req("S2", "RT-EDGE", "10.0.0.2"));

    var page =
        service.search("core", null, null, null, null, null, null, null, PageRequest.of(0, 20));

    assertThat(page.content()).hasSize(1);
    assertThat(page.content().get(0).hostname()).isEqualTo("SW-CORE");
    assertThat(page.totalElements()).isEqualTo(1);
  }
}
