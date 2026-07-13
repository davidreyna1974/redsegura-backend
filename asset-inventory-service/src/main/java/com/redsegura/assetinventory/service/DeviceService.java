package com.redsegura.assetinventory.service;

import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.Device;
import com.redsegura.assetinventory.domain.DeviceStatus;
import com.redsegura.assetinventory.domain.DeviceType;
import com.redsegura.assetinventory.domain.Location;
import com.redsegura.assetinventory.exception.DeviceDecommissionedException;
import com.redsegura.assetinventory.exception.DeviceNotFoundException;
import com.redsegura.assetinventory.exception.DuplicateDeviceException;
import com.redsegura.assetinventory.mapper.DeviceMapper;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.web.dto.DeviceCreateRequest;
import com.redsegura.assetinventory.web.dto.DeviceResponse;
import com.redsegura.assetinventory.web.dto.DeviceUpdateRequest;
import com.redsegura.assetinventory.web.dto.LocationDto;
import com.redsegura.assetinventory.web.dto.PageResponse;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lógica de negocio del inventario (RN1..RN7). Inyección por constructor; transacciones explícitas.
 */
@Service
public class DeviceService {

  private final DeviceRepository repository;
  private final DeviceMapper mapper;

  public DeviceService(DeviceRepository repository, DeviceMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  /** Alta de dispositivo. La unicidad (RN1) la garantiza el índice único, no un chequeo previo. */
  @Transactional
  public DeviceResponse create(DeviceCreateRequest req) {
    Device device =
        new Device(
            req.serialNumber(), req.hostname(), req.mgmtIp(), req.deviceType(), req.criticality());
    device.setAssetTag(req.assetTag());
    device.setVendor(req.vendor());
    device.setModel(req.model());
    device.setLocation(toLocation(req.location()));
    return save(device);
  }

  @Transactional(readOnly = true)
  public DeviceResponse findById(UUID id) {
    return mapper.toResponse(getOrThrow(id));
  }

  @Transactional(readOnly = true)
  public PageResponse<DeviceResponse> search(
      String hostname,
      String mgmtIp,
      String serialNumber,
      DeviceType deviceType,
      String site,
      String rack,
      Criticality criticality,
      DeviceStatus status,
      Pageable pageable) {
    Specification<Device> spec =
        DeviceSpecifications.withFilters(
            hostname, mgmtIp, serialNumber, deviceType, site, rack, criticality, status);
    return PageResponse.from(repository.findAll(spec, pageable).map(mapper::toResponse));
  }

  /**
   * Edición (parcial/merge). {@code serialNumber} y {@code status} no se editan por API (RN2/RN7,
   * no están en el DTO). Un dispositivo dado de baja no es editable (RN6).
   */
  @Transactional
  public DeviceResponse update(UUID id, DeviceUpdateRequest req) {
    Device device = getOrThrow(id);
    if (device.getStatus() == DeviceStatus.BAJA) {
      throw new DeviceDecommissionedException("No se puede editar un dispositivo dado de baja");
    }
    if (req.hostname() != null) {
      device.setHostname(req.hostname());
    }
    if (req.mgmtIp() != null) {
      device.setMgmtIp(req.mgmtIp());
    }
    if (req.deviceType() != null) {
      device.setDeviceType(req.deviceType());
    }
    if (req.vendor() != null) {
      device.setVendor(req.vendor());
    }
    if (req.model() != null) {
      device.setModel(req.model());
    }
    if (req.assetTag() != null) {
      device.setAssetTag(req.assetTag());
    }
    if (req.location() != null) {
      device.setLocation(toLocation(req.location()));
    }
    if (req.criticality() != null) {
      device.setCriticality(req.criticality());
    }
    return save(device);
  }

  /** Baja lógica (soft delete, RN6). */
  @Transactional
  public void decommission(UUID id) {
    Device device = getOrThrow(id);
    device.decommission();
    repository.save(device);
  }

  private DeviceResponse save(Device device) {
    try {
      return mapper.toResponse(repository.saveAndFlush(device));
    } catch (DataIntegrityViolationException e) {
      throw new DuplicateDeviceException("serialNumber, hostname o mgmtIp ya registrado");
    }
  }

  private Device getOrThrow(UUID id) {
    return repository.findById(id).orElseThrow(() -> new DeviceNotFoundException(id));
  }

  private static Location toLocation(LocationDto dto) {
    if (dto == null) {
      return null;
    }
    return new Location(dto.site(), dto.room(), dto.row(), dto.rack(), dto.rackUnit());
  }
}
