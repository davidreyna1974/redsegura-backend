package com.redsegura.assetinventory.exception;

/** Violación de unicidad (serialNumber/hostname/mgmtIp ya registrados), RN1 → 409. */
public class DuplicateDeviceException extends RuntimeException {

  public DuplicateDeviceException(String detail) {
    super(detail);
  }
}
