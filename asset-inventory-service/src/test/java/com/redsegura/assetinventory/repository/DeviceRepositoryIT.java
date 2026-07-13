package com.redsegura.assetinventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.Device;
import com.redsegura.assetinventory.domain.DeviceStatus;
import com.redsegura.assetinventory.domain.DeviceType;
import com.redsegura.assetinventory.domain.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Tests de integración del repositorio contra PostgreSQL real (Flyway + auditoría + unicidad). */
class DeviceRepositoryIT extends AbstractIntegrationTest {

  @Autowired private DeviceRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  private static Device sampleDevice(String serial, String hostname, String mgmtIp) {
    Device d = new Device(serial, hostname, mgmtIp, DeviceType.SWITCH, Criticality.ALTA);
    d.setVendor("Cisco");
    d.setModel("Catalyst 9300");
    d.setLocation(new Location("MX-DC1", "Sala 2", "B", "B07", 12));
    return d;
  }

  /** ADR-07: al guardar se auto-pueblan las columnas de auditoría y la versión inicia en 0. */
  @Test
  void save_populatesAuditColumnsAndVersion() {
    Device saved = repository.save(sampleDevice("FCW-1", "SW1-CORE", "10.0.0.11"));

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getVersion()).isZero();
    assertThat(saved.getStatus()).isEqualTo(DeviceStatus.ACTIVO);
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getCreatedBy()).isEqualTo("system");
    assertThat(saved.getLocation().getRackUnit()).isEqualTo(12);
  }

  /** RN1: el índice único rechaza un serialNumber duplicado (no basta un chequeo previo). */
  @Test
  void save_rejectsDuplicateSerialNumber() {
    repository.saveAndFlush(sampleDevice("DUP-1", "SW1", "10.0.0.1"));

    assertThatThrownBy(() -> repository.saveAndFlush(sampleDevice("DUP-1", "SW2", "10.0.0.2")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** RN1: el índice único rechaza una IP de gestión duplicada. */
  @Test
  void save_rejectsDuplicateMgmtIp() {
    repository.saveAndFlush(sampleDevice("SER-1", "SW1", "10.0.0.5"));

    assertThatThrownBy(() -> repository.saveAndFlush(sampleDevice("SER-2", "SW2", "10.0.0.5")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void findBySerialNumber_returnsDevice() {
    repository.saveAndFlush(sampleDevice("FIND-1", "SW-FIND", "10.0.0.9"));

    assertThat(repository.findBySerialNumber("FIND-1")).isPresent();
    assertThat(repository.existsByHostname("SW-FIND")).isTrue();
    assertThat(repository.existsByMgmtIp("10.0.0.99")).isFalse();
  }
}
