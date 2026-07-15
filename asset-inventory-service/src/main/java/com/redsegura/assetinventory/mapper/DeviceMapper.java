package com.redsegura.assetinventory.mapper;

import com.redsegura.assetinventory.domain.ManagementAddress;
import com.redsegura.assetinventory.generated.model.Ipv4Address;
import com.redsegura.assetinventory.generated.model.Ipv6Address;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Mapper;

/**
 * Mapeo entidad de dominio → DTO generado del contrato (ADR-05/06). Se usan nombres cualificados
 * porque {@code Device} y {@code Location} existen tanto en el dominio como en el modelo generado.
 * Los enums (mismos valores) los convierte MapStruct por nombre; las direcciones de gestión
 * (RF-05a) se mapean por familia según el tipo destino.
 */
@Mapper(componentModel = "spring")
public interface DeviceMapper {

  com.redsegura.assetinventory.generated.model.Device toResponse(
      com.redsegura.assetinventory.domain.Device device);

  com.redsegura.assetinventory.generated.model.Location toDto(
      com.redsegura.assetinventory.domain.Location location);

  /** ManagementAddress → DTO IPv4 (null si no hay dirección; cubre el embebido "todo nulo"). */
  default Ipv4Address toIpv4(ManagementAddress address) {
    if (address == null || address.getAddress() == null) {
      return null;
    }
    return new Ipv4Address()
        .address(address.getAddress())
        .prefixLength(address.getPrefixLength())
        .gateway(address.getGateway());
  }

  /** ManagementAddress → DTO IPv6 (null si no hay dirección). */
  default Ipv6Address toIpv6(ManagementAddress address) {
    if (address == null || address.getAddress() == null) {
      return null;
    }
    return new Ipv6Address()
        .address(address.getAddress())
        .prefixLength(address.getPrefixLength())
        .gateway(address.getGateway());
  }

  /** El contrato usa OffsetDateTime; la entidad usa Instant (UTC). */
  default OffsetDateTime map(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
