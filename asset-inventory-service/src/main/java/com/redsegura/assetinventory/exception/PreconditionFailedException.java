package com.redsegura.assetinventory.exception;

/** El If-Match no coincide con la versión actual del recurso (edición concurrente, RN8) → 412. */
public class PreconditionFailedException extends RuntimeException {

  public PreconditionFailedException(String detail) {
    super(detail);
  }
}
