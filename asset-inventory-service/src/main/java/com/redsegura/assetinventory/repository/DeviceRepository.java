package com.redsegura.assetinventory.repository;

import com.redsegura.assetinventory.domain.Device;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Acceso a datos del inventario. Extiende {@link JpaSpecificationExecutor} para el filtrado
 * dinámico de la búsqueda (§4.2 del contrato) que se implementará en la capa de servicio.
 */
public interface DeviceRepository
    extends JpaRepository<Device, UUID>, JpaSpecificationExecutor<Device> {

  Optional<Device> findBySerialNumber(String serialNumber);

  boolean existsBySerialNumber(String serialNumber);

  boolean existsByHostname(String hostname);

  boolean existsByManagementIpv4Address(String address);

  boolean existsByManagementIpv6Address(String address);
}
