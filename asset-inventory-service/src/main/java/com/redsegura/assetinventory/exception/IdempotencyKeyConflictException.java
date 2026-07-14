package com.redsegura.assetinventory.exception;

/**
 * El {@code Idempotency-Key} ya se usó (para el mismo usuario) con un cuerpo distinto (RN9) → 409.
 * Protege contra el reuso accidental de una clave: en vez de reproducir silenciosamente el recurso
 * original, se rechaza el reintento cuyo contenido no coincide con el de la petición original.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

  public IdempotencyKeyConflictException(String detail) {
    super(detail);
  }
}
