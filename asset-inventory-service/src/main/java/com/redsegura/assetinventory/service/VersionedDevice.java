package com.redsegura.assetinventory.service;

import com.redsegura.assetinventory.generated.model.Device;

/** DTO de salida + su versión (para el ETag de concurrencia optimista, ADR-09). */
public record VersionedDevice(Device body, long version) {}
