package com.redsegura.assetinventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests unitarios del dominio (sin BD): comportamiento de {@link Device} y {@link Location}. */
class DeviceTest {

  private static Device newDevice() {
    Device d = new Device("FCW-1", "SW1-CORE", DeviceType.SWITCH, Criticality.ALTA);
    d.setManagementIpv4(new ManagementAddress("10.0.0.11", 24, "10.0.0.1"));
    d.setAssetTag("A-100");
    d.setVendor("Cisco");
    d.setModel("Catalyst 9300");
    d.setLocation(new Location("MX-DC1", "Sala 2", "B", "B07", 12));
    return d;
  }

  @Test
  void newDevice_hasRequiredFieldsAndDefaultsToActivo() {
    Device d = newDevice();

    assertThat(d.getSerialNumber()).isEqualTo("FCW-1");
    assertThat(d.getHostname()).isEqualTo("SW1-CORE");
    assertThat(d.getManagementIpv4().getAddress()).isEqualTo("10.0.0.11");
    assertThat(d.getManagementIpv4().getPrefixLength()).isEqualTo(24);
    assertThat(d.getDeviceType()).isEqualTo(DeviceType.SWITCH);
    assertThat(d.getCriticality()).isEqualTo(Criticality.ALTA);
    assertThat(d.getAssetTag()).isEqualTo("A-100");
    assertThat(d.getVendor()).isEqualTo("Cisco");
    assertThat(d.getModel()).isEqualTo("Catalyst 9300");
    // Por defecto un dispositivo nace ACTIVO (RN6).
    assertThat(d.getStatus()).isEqualTo(DeviceStatus.ACTIVO);
  }

  /** RN6: dar de baja es lógico (status -> BAJA), no borra. */
  @Test
  void decommission_setsStatusToBaja() {
    Device d = newDevice();

    d.decommission();

    assertThat(d.getStatus()).isEqualTo(DeviceStatus.BAJA);
  }

  @Test
  void setters_updateMutableFields() {
    Device d = newDevice();

    d.setHostname("SW1-EDGE");
    d.setManagementIpv4(new ManagementAddress("10.0.0.20", 24, null));
    d.setDeviceType(DeviceType.ROUTER);
    d.setCriticality(Criticality.MEDIA);
    d.setStatus(DeviceStatus.ACTIVO);

    assertThat(d.getHostname()).isEqualTo("SW1-EDGE");
    assertThat(d.getManagementIpv4().getAddress()).isEqualTo("10.0.0.20");
    assertThat(d.getDeviceType()).isEqualTo(DeviceType.ROUTER);
    assertThat(d.getCriticality()).isEqualTo(Criticality.MEDIA);
  }

  @Test
  void location_getters() {
    Location loc = new Location("MX-DC1", "Sala 2", "B", "B07", 12);

    assertThat(loc.getSite()).isEqualTo("MX-DC1");
    assertThat(loc.getRoom()).isEqualTo("Sala 2");
    assertThat(loc.getRow()).isEqualTo("B");
    assertThat(loc.getRack()).isEqualTo("B07");
    assertThat(loc.getRackUnit()).isEqualTo(12);
  }

  @Test
  void location_equalsAndHashCode() {
    Location a = new Location("MX-DC1", "Sala 2", "B", "B07", 12);
    Location b = new Location("MX-DC1", "Sala 2", "B", "B07", 12);
    Location c = new Location("MX-DC2", "Sala 1", "A", "A01", 1);

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    assertThat(a).isNotEqualTo(c);
    assertThat(a).isEqualTo(a);
    assertThat(a).isNotEqualTo(null);
  }
}
