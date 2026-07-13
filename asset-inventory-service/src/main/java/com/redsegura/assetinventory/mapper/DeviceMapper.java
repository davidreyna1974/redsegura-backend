package com.redsegura.assetinventory.mapper;

import com.redsegura.assetinventory.domain.Device;
import com.redsegura.assetinventory.domain.Location;
import com.redsegura.assetinventory.web.dto.DeviceResponse;
import com.redsegura.assetinventory.web.dto.LocationDto;
import org.mapstruct.Mapper;

/**
 * Mapeo entidad → DTO de salida con MapStruct (ADR-06). La construcción/actualización de la entidad
 * desde las peticiones vive en la capa de servicio (donde se aplican las reglas de negocio).
 */
@Mapper(componentModel = "spring")
public interface DeviceMapper {

  DeviceResponse toResponse(Device device);

  LocationDto toDto(Location location);
}
