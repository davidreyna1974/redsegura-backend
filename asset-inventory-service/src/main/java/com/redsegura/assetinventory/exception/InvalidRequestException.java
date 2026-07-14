package com.redsegura.assetinventory.exception;

/**
 * Parámetro de petición inválido que no cubre Bean Validation (p. ej. un campo de ordenación no
 * permitido) → 400. Evita propagar excepciones internas (RNF-09) como un 500 con detalles de la
 * implementación (p. ej. {@code PropertyReferenceException} de Spring Data).
 */
public class InvalidRequestException extends RuntimeException {

  public InvalidRequestException(String detail) {
    super(detail);
  }
}
