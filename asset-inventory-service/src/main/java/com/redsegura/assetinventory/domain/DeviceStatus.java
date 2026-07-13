package com.redsegura.assetinventory.domain;

/** Estado del ciclo de vida del dispositivo. La baja es lógica (soft delete, RF-02/RN6). */
public enum DeviceStatus {
  ACTIVO,
  BAJA
}
