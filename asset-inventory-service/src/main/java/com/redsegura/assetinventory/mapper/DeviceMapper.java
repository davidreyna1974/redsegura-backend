package com.redsegura.assetinventory.mapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Mapper;

/**
 * Mapeo entidad de dominio → DTO generado del contrato (ADR-05/06). Se usan nombres cualificados
 * porque {@code Device} y {@code Location} existen tanto en el dominio como en el modelo generado.
 * Los enums (mismos valores) los convierte MapStruct por nombre.
 */
@Mapper(componentModel = "spring")
public interface DeviceMapper {

  com.redsegura.assetinventory.generated.model.Device toResponse(
      com.redsegura.assetinventory.domain.Device device);

  com.redsegura.assetinventory.generated.model.Location toDto(
      com.redsegura.assetinventory.domain.Location location);

  /** El contrato usa OffsetDateTime; la entidad usa Instant (UTC). */
  default OffsetDateTime map(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
