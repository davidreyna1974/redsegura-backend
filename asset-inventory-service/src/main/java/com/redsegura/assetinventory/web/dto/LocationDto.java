package com.redsegura.assetinventory.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Ubicación física estructurada (DCIM) en el contrato de API. */
public record LocationDto(
    String site, String room, String row, String rack, @Min(1) @Max(60) Integer rackUnit) {}
