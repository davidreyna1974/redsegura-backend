package com.redsegura.assetinventory.service;

import com.google.common.net.InetAddresses;
import com.redsegura.assetinventory.domain.ManagementAddress;
import com.redsegura.assetinventory.exception.InvalidAddressException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.springframework.stereotype.Component;

/**
 * Valida y canonicaliza direcciones de gestión (RF-05a/ADR-13). Usa Guava {@code InetAddresses}
 * (parseo estricto, <b>sin</b> resolución DNS) y produce la forma canónica: para IPv6, la forma
 * comprimida RFC 5952, de modo que dos escrituras textuales de la misma dirección se guarden igual
 * y la unicidad sea real. El rango de {@code prefixLength} lo valida Bean Validation del contrato.
 */
@Component
public class IpAddressNormalizer {

  /** Normaliza una dirección IPv4; lanza 422 si el formato/familia es inválido. */
  public ManagementAddress normalizeIpv4(String address, Integer prefixLength, String gateway) {
    return new ManagementAddress(
        canonical(address, Inet4Address.class, "dirección IPv4"),
        prefixLength,
        gateway == null ? null : canonical(gateway, Inet4Address.class, "gateway IPv4"));
  }

  /** Normaliza una dirección IPv6 a su forma canónica (RFC 5952); lanza 422 si es inválida. */
  public ManagementAddress normalizeIpv6(String address, Integer prefixLength, String gateway) {
    return new ManagementAddress(
        canonical(address, Inet6Address.class, "dirección IPv6"),
        prefixLength,
        gateway == null ? null : canonical(gateway, Inet6Address.class, "gateway IPv6"));
  }

  /** Canoniza una IP si es parseable (para búsqueda); si no lo es, devuelve el valor tal cual. */
  public String canonicalizeForSearch(String value) {
    if (value == null) {
      return null;
    }
    try {
      return InetAddresses.toAddrString(InetAddresses.forString(value));
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String canonical(String value, Class<? extends InetAddress> family, String label) {
    InetAddress ip;
    try {
      ip = InetAddresses.forString(value);
    } catch (IllegalArgumentException e) {
      throw new InvalidAddressException(label + " con formato inválido: " + value);
    }
    if (!family.isInstance(ip)) {
      throw new InvalidAddressException(label + " no corresponde a la familia esperada: " + value);
    }
    return InetAddresses.toAddrString(ip);
  }
}
