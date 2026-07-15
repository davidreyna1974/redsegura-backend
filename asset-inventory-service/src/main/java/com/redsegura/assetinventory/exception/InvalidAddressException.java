package com.redsegura.assetinventory.exception;

/**
 * Dirección de gestión inválida por semántica de red (RF-05a): formato IPv4/IPv6 incorrecto,
 * familia equivocada, o ninguna dirección presente. La entidad está bien formada pero es
 * inaceptable → 422.
 */
public class InvalidAddressException extends RuntimeException {

  public InvalidAddressException(String detail) {
    super(detail);
  }
}
