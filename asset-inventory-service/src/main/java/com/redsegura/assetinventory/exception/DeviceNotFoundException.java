package com.redsegura.assetinventory.exception;

import java.util.UUID;

/** El dispositivo solicitado no existe → 404. */
public class DeviceNotFoundException extends RuntimeException {

  public DeviceNotFoundException(UUID id) {
    super("Dispositivo no encontrado: " + id);
  }
}
