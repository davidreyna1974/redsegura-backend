package com.redsegura.assetinventory.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.util.UUID;

/**
 * Dispositivo de red del inventario — entidad raíz del servicio (fuente de verdad).
 *
 * <p>Notas de diseño no obvias:
 *
 * <ul>
 *   <li><b>Identidad:</b> {@code serialNumber} es la identidad estable de negocio (inmutable,
 *       {@code updatable=false}, RN2). {@code hostname} y {@code mgmtIp} son únicos pero mutables.
 *   <li><b>Unicidad (RN1):</b> restricciones únicas en BD sobre serial, hostname y mgmtIp — la
 *       verificación real es el índice único, no un chequeo previo (evita carreras).
 *   <li><b>Concurrencia (RN8):</b> {@code @Version} da bloqueo optimista; se expone por HTTP como
 *       {@code ETag}/{@code If-Match} (ADR-09).
 *   <li><b>Baja lógica (RN6):</b> el {@code status} pasa a {@code BAJA}, no se borra la fila.
 * </ul>
 */
@Entity
@Table(
    name = "devices",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_devices_serial", columnNames = "serial_number"),
      @UniqueConstraint(name = "uq_devices_hostname", columnNames = "hostname"),
      @UniqueConstraint(name = "uq_devices_mgmt_ipv4", columnNames = "mgmt_ipv4_address"),
      @UniqueConstraint(name = "uq_devices_mgmt_ipv6", columnNames = "mgmt_ipv6_address")
    })
public class Device extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "serial_number", nullable = false, updatable = false)
  private String serialNumber;

  @Column(name = "asset_tag")
  private String assetTag;

  @Column(nullable = false)
  private String hostname;

  /** Dirección de gestión IPv4 (RF-05a); nula si el dispositivo solo tiene IPv6. */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "address", column = @Column(name = "mgmt_ipv4_address", length = 15)),
    @AttributeOverride(name = "prefixLength", column = @Column(name = "mgmt_ipv4_prefix")),
    @AttributeOverride(name = "gateway", column = @Column(name = "mgmt_ipv4_gateway", length = 15))
  })
  private ManagementAddress managementIpv4;

  /** Dirección de gestión IPv6 canonicalizada (RF-05a); nula si el dispositivo solo tiene IPv4. */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "address", column = @Column(name = "mgmt_ipv6_address", length = 45)),
    @AttributeOverride(name = "prefixLength", column = @Column(name = "mgmt_ipv6_prefix")),
    @AttributeOverride(name = "gateway", column = @Column(name = "mgmt_ipv6_gateway", length = 45))
  })
  private ManagementAddress managementIpv6;

  @Enumerated(EnumType.STRING)
  @Column(name = "device_type", nullable = false)
  private DeviceType deviceType;

  private String vendor;

  private String model;

  @Embedded private Location location;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Criticality criticality;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DeviceStatus status = DeviceStatus.ACTIVO;

  /** Versión para bloqueo optimista (RN8 / ETag). Gestionada por Hibernate. */
  @Version private Long version;

  /** Constructor sin argumentos requerido por JPA. */
  protected Device() {}

  /**
   * Crea un dispositivo con sus campos obligatorios (RF-01/RN3). Las direcciones de gestión
   * (RF-05a) se asignan aparte con {@link #setManagementIpv4}/{@link #setManagementIpv6}; al menos
   * una debe quedar presente (validado en el servicio y por CHECK en BD).
   */
  public Device(
      String serialNumber, String hostname, DeviceType deviceType, Criticality criticality) {
    this.serialNumber = serialNumber;
    this.hostname = hostname;
    this.deviceType = deviceType;
    this.criticality = criticality;
  }

  public UUID getId() {
    return id;
  }

  public String getSerialNumber() {
    return serialNumber;
  }

  public void setSerialNumber(String serialNumber) {
    this.serialNumber = serialNumber;
  }

  public String getAssetTag() {
    return assetTag;
  }

  public void setAssetTag(String assetTag) {
    this.assetTag = assetTag;
  }

  public String getHostname() {
    return hostname;
  }

  public void setHostname(String hostname) {
    this.hostname = hostname;
  }

  public ManagementAddress getManagementIpv4() {
    return managementIpv4;
  }

  public void setManagementIpv4(ManagementAddress managementIpv4) {
    this.managementIpv4 = managementIpv4;
  }

  public ManagementAddress getManagementIpv6() {
    return managementIpv6;
  }

  public void setManagementIpv6(ManagementAddress managementIpv6) {
    this.managementIpv6 = managementIpv6;
  }

  public DeviceType getDeviceType() {
    return deviceType;
  }

  public void setDeviceType(DeviceType deviceType) {
    this.deviceType = deviceType;
  }

  public String getVendor() {
    return vendor;
  }

  public void setVendor(String vendor) {
    this.vendor = vendor;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public Location getLocation() {
    return location;
  }

  public void setLocation(Location location) {
    this.location = location;
  }

  public Criticality getCriticality() {
    return criticality;
  }

  public void setCriticality(Criticality criticality) {
    this.criticality = criticality;
  }

  public DeviceStatus getStatus() {
    return status;
  }

  public void setStatus(DeviceStatus status) {
    this.status = status;
  }

  public Long getVersion() {
    return version;
  }

  /** Marca el dispositivo como dado de baja (soft delete, RN6). */
  public void decommission() {
    this.status = DeviceStatus.BAJA;
  }
}
