package com.redsegura.assetinventory.service;

import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.DeviceStatus;
import com.redsegura.assetinventory.domain.DeviceType;
import com.redsegura.assetinventory.domain.Location;
import com.redsegura.assetinventory.exception.DeviceDecommissionedException;
import com.redsegura.assetinventory.exception.DeviceNotFoundException;
import com.redsegura.assetinventory.exception.DuplicateDeviceException;
import com.redsegura.assetinventory.generated.model.Device;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import com.redsegura.assetinventory.generated.model.DeviceUpdateFull;
import com.redsegura.assetinventory.generated.model.DeviceUpdateRequest;
import com.redsegura.assetinventory.mapper.DeviceMapper;
import com.redsegura.assetinventory.repository.DeviceRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lógica de negocio del inventario (RN1..RN7). Recibe/devuelve los DTOs generados del contrato
 * (ADR-05) y opera sobre la entidad de dominio; los enums generados se puentean por nombre.
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
  public Device create(DeviceCreateRequest req) {
    var entity =
        new com.redsegura.assetinventory.domain.Device(
            req.getSerialNumber(),
            req.getHostname(),
            req.getMgmtIp(),
            toDomainType(req.getDeviceType()),
            toDomainCriticality(req.getCriticality()));
    entity.setAssetTag(req.getAssetTag());
    entity.setVendor(req.getVendor());
    entity.setModel(req.getModel());
    entity.setLocation(toDomainLocation(req.getLocation()));
    return save(entity);
  }

  @Transactional(readOnly = true)
  public Device findById(UUID id) {
    return mapper.toResponse(getOrThrow(id));
  }

  @Transactional(readOnly = true)
  public Page<Device> search(
      String hostname,
      String mgmtIp,
      String serialNumber,
      DeviceType deviceType,
      String site,
      String rack,
      Criticality criticality,
      DeviceStatus status,
      Pageable pageable) {
    Specification<com.redsegura.assetinventory.domain.Device> spec =
        DeviceSpecifications.withFilters(
            hostname, mgmtIp, serialNumber, deviceType, site, rack, criticality, status);
    return repository.findAll(spec, pageable).map(mapper::toResponse);
  }

  /** Reemplazo completo (PUT). */
  @Transactional
  public Device replace(UUID id, DeviceUpdateFull req) {
    return applyUpdate(
        id,
        req.getHostname(),
        req.getMgmtIp(),
        req.getDeviceType(),
        req.getVendor(),
        req.getModel(),
        req.getAssetTag(),
        req.getLocation(),
        req.getCriticality());
  }

  /** Edición parcial (PATCH, JSON Merge Patch). */
  @Transactional
  public Device update(UUID id, DeviceUpdateRequest req) {
    return applyUpdate(
        id,
        req.getHostname(),
        req.getMgmtIp(),
        req.getDeviceType(),
        req.getVendor(),
        req.getModel(),
        req.getAssetTag(),
        req.getLocation(),
        req.getCriticality());
  }

  /** Baja lógica (soft delete, RN6). */
  @Transactional
  public void decommission(UUID id) {
    var entity = getOrThrow(id);
    entity.decommission();
    repository.save(entity);
  }

  /**
   * Aplica los campos no nulos a la entidad. {@code serialNumber} y {@code status} no se editan por
   * API (RN2/RN7). Un dispositivo dado de baja no es editable (RN6).
   */
  private Device applyUpdate(
      UUID id,
      String hostname,
      String mgmtIp,
      com.redsegura.assetinventory.generated.model.DeviceType deviceType,
      String vendor,
      String model,
      String assetTag,
      com.redsegura.assetinventory.generated.model.Location location,
      com.redsegura.assetinventory.generated.model.Criticality criticality) {
    var entity = getOrThrow(id);
    if (entity.getStatus() == DeviceStatus.BAJA) {
      throw new DeviceDecommissionedException("No se puede editar un dispositivo dado de baja");
    }
    if (hostname != null) {
      entity.setHostname(hostname);
    }
    if (mgmtIp != null) {
      entity.setMgmtIp(mgmtIp);
    }
    if (deviceType != null) {
      entity.setDeviceType(toDomainType(deviceType));
    }
    if (vendor != null) {
      entity.setVendor(vendor);
    }
    if (model != null) {
      entity.setModel(model);
    }
    if (assetTag != null) {
      entity.setAssetTag(assetTag);
    }
    if (location != null) {
      entity.setLocation(toDomainLocation(location));
    }
    if (criticality != null) {
      entity.setCriticality(toDomainCriticality(criticality));
    }
    return save(entity);
  }

  private Device save(com.redsegura.assetinventory.domain.Device entity) {
    try {
      return mapper.toResponse(repository.saveAndFlush(entity));
    } catch (DataIntegrityViolationException e) {
      throw new DuplicateDeviceException("serialNumber, hostname o mgmtIp ya registrado");
    }
  }

  private com.redsegura.assetinventory.domain.Device getOrThrow(UUID id) {
    return repository.findById(id).orElseThrow(() -> new DeviceNotFoundException(id));
  }

  private static DeviceType toDomainType(
      com.redsegura.assetinventory.generated.model.DeviceType t) {
    return t == null ? null : DeviceType.valueOf(t.name());
  }

  private static Criticality toDomainCriticality(
      com.redsegura.assetinventory.generated.model.Criticality c) {
    return c == null ? null : Criticality.valueOf(c.name());
  }

  private static Location toDomainLocation(
      com.redsegura.assetinventory.generated.model.Location l) {
    return l == null
        ? null
        : new Location(l.getSite(), l.getRoom(), l.getRow(), l.getRack(), l.getRackUnit());
  }
}
