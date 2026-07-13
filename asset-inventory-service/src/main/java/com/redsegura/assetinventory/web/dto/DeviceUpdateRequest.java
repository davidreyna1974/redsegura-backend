package com.redsegura.assetinventory.web.dto;

import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.DeviceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Petición de edición (parcial en PATCH / completa en PUT). Todos los campos son opcionales a nivel
 * de tipo; el servicio aplica solo los no nulos (semántica JSON Merge Patch). {@code serialNumber}
 * no está presente porque es inmutable (RN2).
 */
public record DeviceUpdateRequest(
    @Size(max = 100) String assetTag,
    @Size(max = 255) String hostname,
    @Pattern(regexp = ValidationPatterns.IPV4, message = "mgmtIp debe ser una IPv4 válida")
        String mgmtIp,
    DeviceType deviceType,
    String vendor,
    String model,
    @Valid LocationDto location,
    Criticality criticality) {}
