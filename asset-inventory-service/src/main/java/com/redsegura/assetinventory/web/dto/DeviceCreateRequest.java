package com.redsegura.assetinventory.web.dto;

import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.DeviceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Petición de alta de dispositivo. La validación de campos (RN3/RN4/RN5) la aplica Bean Validation;
 * la unicidad (RN1) se verifica en la capa de servicio contra el índice único.
 */
public record DeviceCreateRequest(
    @NotBlank @Size(max = 100) String serialNumber,
    @Size(max = 100) String assetTag,
    @NotBlank @Size(max = 255) String hostname,
    @NotBlank
        @Pattern(regexp = ValidationPatterns.IPV4, message = "mgmtIp debe ser una IPv4 válida")
        String mgmtIp,
    @NotNull DeviceType deviceType,
    String vendor,
    String model,
    @Valid LocationDto location,
    @NotNull Criticality criticality) {}
