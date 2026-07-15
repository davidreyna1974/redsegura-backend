package com.redsegura.assetinventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.exception.DeviceDecommissionedException;
import com.redsegura.assetinventory.exception.DeviceNotFoundException;
import com.redsegura.assetinventory.exception.DuplicateDeviceException;
import com.redsegura.assetinventory.exception.IdempotencyKeyConflictException;
import com.redsegura.assetinventory.exception.InvalidAddressException;
import com.redsegura.assetinventory.exception.PreconditionFailedException;
import com.redsegura.assetinventory.generated.model.Criticality;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import com.redsegura.assetinventory.generated.model.DeviceStatus;
import com.redsegura.assetinventory.generated.model.DeviceType;
import com.redsegura.assetinventory.generated.model.DeviceUpdateRequest;
import com.redsegura.assetinventory.generated.model.Ipv4Address;
import com.redsegura.assetinventory.generated.model.Ipv6Address;
import com.redsegura.assetinventory.generated.model.Location;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.IdempotencyRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Reglas de negocio del inventario (RN1..RN8) contra PostgreSQL real, con los DTOs del contrato.
 */
class DeviceServiceIT extends AbstractIntegrationTest {

  @Autowired private DeviceService service;
  @Autowired private DeviceRepository repository;
  @Autowired private IdempotencyRepository idempotencyRepository;

  @BeforeEach
  void clean() {
    idempotencyRepository.deleteAll();
    repository.deleteAll();
  }

  private static DeviceCreateRequest req(String serial, String hostname, String ip) {
    return new DeviceCreateRequest(serial, hostname, DeviceType.SWITCH, Criticality.ALTA)
        .managementIpv4(new Ipv4Address().address(ip).prefixLength(24).gateway("10.0.0.254"))
        .assetTag("A-1")
        .vendor("Cisco")
        .model("C9300")
        .location(new Location().site("MX-DC1").room("Sala 2").row("B").rack("B07").rackUnit(12));
  }

  @Test
  void create_thenFindById() {
    VersionedDevice created = service.create(req("S1", "SW1", "10.0.0.1"), null);

    assertThat(created.body().getId()).isNotNull();
    assertThat(created.version()).isZero();
    VersionedDevice found = service.findById(created.body().getId());
    assertThat(found.body().getSerialNumber()).isEqualTo("S1");
    assertThat(found.body().getStatus()).isEqualTo(DeviceStatus.ACTIVO);
    assertThat(found.body().getCreatedBy()).isEqualTo("system");
    assertThat(found.body().getLocation().getSite()).isEqualTo("MX-DC1");
  }

  /** RN1: serialNumber duplicado -> 409. */
  @Test
  void create_duplicateSerial_throwsDuplicate() {
    service.create(req("DUP", "SW1", "10.0.0.1"), null);

    assertThatThrownBy(() -> service.create(req("DUP", "SW2", "10.0.0.2"), null))
        .isInstanceOf(DuplicateDeviceException.class);
  }

  @Test
  void findById_missing_throwsNotFound() {
    assertThatThrownBy(() -> service.findById(UUID.randomUUID()))
        .isInstanceOf(DeviceNotFoundException.class);
  }

  @Test
  void update_appliesOnlyProvidedFields_andBumpsVersion() {
    VersionedDevice c = service.create(req("S1", "SW1", "10.0.0.1"), null);

    VersionedDevice updated =
        service.update(
            c.body().getId(),
            c.version(),
            new DeviceUpdateRequest()
                .hostname("SW1-NEW")
                .deviceType(DeviceType.ROUTER)
                .criticality(Criticality.MEDIA));

    assertThat(updated.body().getHostname()).isEqualTo("SW1-NEW");
    assertThat(updated.body().getDeviceType()).isEqualTo(DeviceType.ROUTER);
    assertThat(updated.body().getManagementIpv4().getAddress()).isEqualTo("10.0.0.1"); // no cambia
    assertThat(updated.body().getSerialNumber()).isEqualTo("S1"); // inmutable (RN2)
    assertThat(updated.version()).isEqualTo(1L); // version incrementada
  }

  /** RN8: If-Match desactualizado -> 412. */
  @Test
  void update_wrongVersion_throwsPreconditionFailed() {
    VersionedDevice c = service.create(req("S1", "SW1", "10.0.0.1"), null);

    assertThatThrownBy(
            () -> service.update(c.body().getId(), 999L, new DeviceUpdateRequest().hostname("X")))
        .isInstanceOf(PreconditionFailedException.class);
  }

  /** RN6: no se puede editar un dispositivo dado de baja (con If-Match correcto). */
  @Test
  void update_decommissioned_throwsConflict() {
    VersionedDevice c = service.create(req("S1", "SW1", "10.0.0.1"), null);
    service.decommission(c.body().getId(), c.version());
    long currentVersion = service.findById(c.body().getId()).version();

    assertThatThrownBy(
            () ->
                service.update(
                    c.body().getId(), currentVersion, new DeviceUpdateRequest().hostname("X")))
        .isInstanceOf(DeviceDecommissionedException.class);
  }

  /** RN6/FLOW-01: la baja es lógica y se excluye de los listados salvo filtro estado=BAJA. */
  @Test
  void decommission_isSoftAndExcludedFromDefaultSearch() {
    VersionedDevice c = service.create(req("S1", "SW1", "10.0.0.1"), null);

    service.decommission(c.body().getId(), c.version());

    assertThat(service.findById(c.body().getId()).body().getStatus()).isEqualTo(DeviceStatus.BAJA);
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
                    null,
                    null,
                    null,
                    PageRequest.of(0, 20))
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
                    null,
                    null,
                    com.redsegura.assetinventory.domain.DeviceStatus.BAJA,
                    PageRequest.of(0, 20))
                .getContent())
        .hasSize(1);
  }

  @Test
  void search_filtersByHostnameCaseInsensitive() {
    service.create(req("S1", "SW-CORE", "10.0.0.1"), null);
    service.create(req("S2", "RT-EDGE", "10.0.0.2"), null);

    var page =
        service.search(
            "core", null, null, null, null, null, null, null, null, null, PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).getHostname()).isEqualTo("SW-CORE");
    assertThat(page.getTotalElements()).isEqualTo(1);
  }

  /** BSRCH-02: la búsqueda por hostname es insensible a acentos además de mayúsculas. */
  @Test
  void search_isAccentInsensitive() {
    service.create(req("S1", "Galón-Core", "10.0.0.1"), null);
    service.create(req("S2", "RT-EDGE", "10.0.0.2"), null);

    var page =
        service.search(
            "galon", null, null, null, null, null, null, null, null, null, PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).getHostname()).isEqualTo("Galón-Core");
  }

  /** El filtro por fabricante (vendor) es parcial e insensible a mayúsculas. */
  @Test
  void search_filtersByVendor() {
    service.create(req("S1", "SW1", "10.0.0.1"), null); // vendor Cisco (del helper req)
    service.create(req("S2", "SW2", "10.0.0.2").vendor("Juniper"), null);

    var page =
        service.search(
            null, null, null, null, null, null, null, "cisco", null, null, PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).getSerialNumber()).isEqualTo("S1");
  }

  /**
   * RN9: un POST con la misma Idempotency-Key y el mismo cuerpo devuelve el original y no duplica.
   */
  @Test
  void create_withSameIdempotencyKeyAndBody_isReplayed() {
    VersionedDevice first = service.create(req("S1", "SW1", "10.0.0.1"), "key-123");

    // Reintento idéntico (misma clave, mismo cuerpo): devuelve el original sin duplicar.
    VersionedDevice replay = service.create(req("S1", "SW1", "10.0.0.1"), "key-123");

    assertThat(replay.body().getId()).isEqualTo(first.body().getId());
    assertThat(replay.body().getSerialNumber()).isEqualTo("S1");
    assertThat(repository.count()).isEqualTo(1);
  }

  /**
   * RN9: reuso de la misma Idempotency-Key con un cuerpo distinto -> 409 (no replay silencioso).
   */
  @Test
  void create_withSameIdempotencyKeyDifferentBody_throwsConflict() {
    service.create(req("S1", "SW1", "10.0.0.1"), "key-123");

    assertThatThrownBy(() -> service.create(req("S2", "SW2", "10.0.0.2"), "key-123"))
        .isInstanceOf(IdempotencyKeyConflictException.class);
    assertThat(repository.count()).isEqualTo(1);
  }

  private static DeviceCreateRequest bare(String serial, String hostname) {
    return new DeviceCreateRequest(serial, hostname, DeviceType.SWITCH, Criticality.ALTA);
  }

  /** RF-05a/IP-02: la IPv6 se guarda en forma canónica (RFC 5952). */
  @Test
  void create_withNonCanonicalIpv6_storesCanonicalForm() {
    var created =
        service.create(
            bare("S1", "SW1")
                .managementIpv6(
                    new Ipv6Address()
                        .address("2001:0DB8:0000:0000:0000:0000:0000:0011")
                        .prefixLength(64)),
            null);

    assertThat(created.body().getManagementIpv6().getAddress()).isEqualTo("2001:db8::11");
    assertThat(created.body().getManagementIpv4()).isNull();
  }

  /** RF-05a/IP-03: dual-stack (IPv4 + IPv6) se guardan ambas. */
  @Test
  void create_dualStack_storesBoth() {
    var created =
        service.create(
            bare("S1", "SW1")
                .managementIpv4(new Ipv4Address().address("10.0.0.1").prefixLength(24))
                .managementIpv6(new Ipv6Address().address("2001:db8::1").prefixLength(64)),
            null);

    assertThat(created.body().getManagementIpv4().getAddress()).isEqualTo("10.0.0.1");
    assertThat(created.body().getManagementIpv6().getAddress()).isEqualTo("2001:db8::1");
  }

  /** RF-05a/IP-04: sin ninguna dirección de gestión -> 422. */
  @Test
  void create_withoutAnyManagementAddress_throws() {
    assertThatThrownBy(() -> service.create(bare("S1", "SW1"), null))
        .isInstanceOf(InvalidAddressException.class);
  }

  /** RF-05a/IP-05: familia equivocada (IPv6 en el campo IPv4) -> 422. */
  @Test
  void create_withWrongFamily_throws() {
    var req =
        bare("S1", "SW1")
            .managementIpv4(new Ipv4Address().address("2001:db8::11").prefixLength(24));

    assertThatThrownBy(() -> service.create(req, null)).isInstanceOf(InvalidAddressException.class);
  }

  /** RF-05a/IP-06: unicidad IPv6 con formas textuales distintas de la misma dirección -> 409. */
  @Test
  void create_duplicateIpv6DifferentForm_throwsDuplicate() {
    service.create(
        bare("S1", "SW1")
            .managementIpv6(new Ipv6Address().address("2001:db8::11").prefixLength(64)),
        null);

    assertThatThrownBy(
            () ->
                service.create(
                    bare("S2", "SW2")
                        .managementIpv6(
                            new Ipv6Address()
                                .address("2001:0db8:0000:0000:0000:0000:0000:0011")
                                .prefixLength(64)),
                    null))
        .isInstanceOf(DuplicateDeviceException.class);
  }
}
