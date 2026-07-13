package com.redsegura.assetinventory.web.dto;

import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.DeviceStatus;
import com.redsegura.assetinventory.domain.DeviceType;
import java.time.Instant;
import java.util.UUID;

/**
 * Representación de salida de un dispositivo (contrato). Incluye columnas de auditoría (ADR-07).
 */
public record DeviceResponse(
    UUID id,
    String serialNumber,
    String assetTag,
    String hostname,
    String mgmtIp,
    DeviceType deviceType,
    String vendor,
    String model,
    LocationDto location,
    Criticality criticality,
    DeviceStatus status,
    Instant createdAt,
    String createdBy,
    Instant updatedAt,
    String updatedBy) {}
