package com.redsegura.assetinventory.web.dto;

/** Patrones de validación reutilizables. */
public final class ValidationPatterns {

  /**
   * IPv4 con octetos 0–255 (RN5). El soporte IPv6 se contempla como mejora futura; la red simulada
   * del proyecto es IPv4.
   */
  public static final String IPV4 =
      "^((25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d?\\d)$";

  private ValidationPatterns() {}
}
