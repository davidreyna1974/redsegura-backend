package com.redsegura.assetinventory.exception;

/** Intento de editar un dispositivo dado de baja (RN6) → 409. */
public class DeviceDecommissionedException extends RuntimeException {

  public DeviceDecommissionedException(String detail) {
    super(detail);
  }
}
