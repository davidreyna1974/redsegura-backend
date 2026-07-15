package com.redsegura.assetinventory.domain;

import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * Dirección de gestión (IPv4 o IPv6) con su información complementaria (RF-05a): la dirección (ya
 * validada y, si es IPv6, canonicalizada RFC 5952), el prefijo de red ({@code prefixLength}/CIDR) y
 * la puerta de enlace opcional. Se embebe dos veces en {@link Device} (una por familia) con nombres
 * de columna distintos.
 */
@Embeddable
public class ManagementAddress {

  private String address;
  private Integer prefixLength;
  private String gateway;

  protected ManagementAddress() {}

  public ManagementAddress(String address, Integer prefixLength, String gateway) {
    this.address = address;
    this.prefixLength = prefixLength;
    this.gateway = gateway;
  }

  public String getAddress() {
    return address;
  }

  public Integer getPrefixLength() {
    return prefixLength;
  }

  public String getGateway() {
    return gateway;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ManagementAddress other)) {
      return false;
    }
    return Objects.equals(address, other.address)
        && Objects.equals(prefixLength, other.prefixLength)
        && Objects.equals(gateway, other.gateway);
  }

  @Override
  public int hashCode() {
    return Objects.hash(address, prefixLength, gateway);
  }
}
