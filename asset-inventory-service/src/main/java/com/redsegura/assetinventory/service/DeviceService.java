package com.redsegura.assetinventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.DeviceStatus;
import com.redsegura.assetinventory.domain.DeviceType;
import com.redsegura.assetinventory.domain.IdempotencyRecord;
import com.redsegura.assetinventory.domain.Location;
import com.redsegura.assetinventory.exception.DeviceDecommissionedException;
import com.redsegura.assetinventory.exception.DeviceNotFoundException;
import com.redsegura.assetinventory.exception.DuplicateDeviceException;
import com.redsegura.assetinventory.exception.IdempotencyKeyConflictException;
import com.redsegura.assetinventory.exception.PreconditionFailedException;
import com.redsegura.assetinventory.generated.model.Device;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import com.redsegura.assetinventory.generated.model.DeviceUpdateFull;
import com.redsegura.assetinventory.generated.model.DeviceUpdateRequest;
import com.redsegura.assetinventory.mapper.DeviceMapper;
import com.redsegura.assetinventory.messaging.OutboxWriter;
import com.redsegura.assetinventory.messaging.RabbitConfig;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.IdempotencyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lógica de negocio del inventario (RN1..RN11). Recibe/devuelve los DTOs generados del contrato
 * (ADR-05) y opera sobre la entidad de dominio; los enums generados se puentean por nombre. Las
 * lecturas y escrituras devuelven la versión para el ETag (ADR-09).
 */
@Service
public class DeviceService {

  private final DeviceRepository repository;
  private final IdempotencyRepository idempotencyRepository;
  private final DeviceMapper mapper;
  private final AuditorAware<String> auditorAware;
  private final ObjectMapper objectMapper;
  private final OutboxWriter outboxWriter;

  public DeviceService(
      DeviceRepository repository,
      IdempotencyRepository idempotencyRepository,
      DeviceMapper mapper,
      AuditorAware<String> auditorAware,
      ObjectMapper objectMapper,
      OutboxWriter outboxWriter) {
    this.repository = repository;
    this.idempotencyRepository = idempotencyRepository;
    this.mapper = mapper;
    this.auditorAware = auditorAware;
    this.objectMapper = objectMapper;
    this.outboxWriter = outboxWriter;
  }

  /**
   * Alta de dispositivo. La unicidad (RN1) la garantiza el índice único, no un chequeo previo.
   *
   * <p>Idempotencia (RN9): la clave se acota por usuario ({@code created_by}) —dos clientes
   * distintos pueden usar la misma cadena sin colisionar— y se compara contra el hash del cuerpo
   * original:
   *
   * <ul>
   *   <li>misma clave + mismo cuerpo → se devuelve el dispositivo original (replay), sin duplicar;
   *   <li>misma clave + cuerpo distinto → 409 ({@link IdempotencyKeyConflictException}), en vez de
   *       reproducir silenciosamente un recurso que no corresponde a esta petición.
   * </ul>
   *
   * La clave (con su hash y dispositivo) se guarda en la misma transacción que el alta.
   */
  @Transactional
  public VersionedDevice create(DeviceCreateRequest req, String idempotencyKey) {
    boolean hasKey = idempotencyKey != null && !idempotencyKey.isBlank();
    String requestHash = null;
    String currentUser = null;
    if (hasKey) {
      requestHash = hashOf(req);
      currentUser = auditorAware.getCurrentAuditor().orElse("system");
      Optional<IdempotencyRecord> existing =
          idempotencyRepository.findByIdKeyAndCreatedBy(idempotencyKey, currentUser);
      if (existing.isPresent()) {
        if (!existing.get().getRequestHash().equals(requestHash)) {
          throw new IdempotencyKeyConflictException(
              "El Idempotency-Key ya se usó con un cuerpo distinto");
        }
        return findById(existing.get().getDeviceId());
      }
    }
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
    VersionedDevice created = save(entity);
    outboxWriter.record(RabbitConfig.ROUTING_ASSET_CREATED, created.body(), null);
    if (hasKey) {
      idempotencyRepository.saveAndFlush(
          new IdempotencyRecord(idempotencyKey, currentUser, requestHash, created.body().getId()));
    }
    return created;
  }

  /** SHA-256 (hex) de la representación canónica del cuerpo, como huella para detectar reuso. */
  private String hashOf(DeviceCreateRequest req) {
    try {
      byte[] canonical = objectMapper.writeValueAsBytes(req);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("No se pudo calcular el hash del cuerpo de la petición", e);
    }
  }

  @Transactional(readOnly = true)
  public VersionedDevice findById(UUID id) {
    var entity = getOrThrow(id);
    return new VersionedDevice(mapper.toResponse(entity), entity.getVersion());
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
      String vendor,
      String model,
      DeviceStatus status,
      Pageable pageable) {
    Specification<com.redsegura.assetinventory.domain.Device> spec =
        DeviceSpecifications.withFilters(
            hostname, mgmtIp, serialNumber, deviceType, site, rack, criticality, vendor, model,
            status);
    return repository.findAll(spec, pageable).map(mapper::toResponse);
  }

  /** Reemplazo completo (PUT). */
  @Transactional
  public VersionedDevice replace(UUID id, long expectedVersion, DeviceUpdateFull req) {
    return applyUpdate(
        id,
        expectedVersion,
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
  public VersionedDevice update(UUID id, long expectedVersion, DeviceUpdateRequest req) {
    return applyUpdate(
        id,
        expectedVersion,
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
  public void decommission(UUID id, long expectedVersion) {
    var entity = getOrThrow(id);
    checkVersion(entity, expectedVersion);
    entity.decommission();
    var saved = saveEntity(entity);
    outboxWriter.record(RabbitConfig.ROUTING_ASSET_DECOMMISSIONED, mapper.toResponse(saved), null);
  }

  /**
   * Aplica los campos no nulos a la entidad. {@code serialNumber} y {@code status} no se editan por
   * API (RN2/RN7). Un dispositivo dado de baja no es editable (RN6). Se valida el {@code If-Match}
   * contra la versión actual (RN8).
   */
  private VersionedDevice applyUpdate(
      UUID id,
      long expectedVersion,
      String hostname,
      String mgmtIp,
      com.redsegura.assetinventory.generated.model.DeviceType deviceType,
      String vendor,
      String model,
      String assetTag,
      com.redsegura.assetinventory.generated.model.Location location,
      com.redsegura.assetinventory.generated.model.Criticality criticality) {
    var entity = getOrThrow(id);
    checkVersion(entity, expectedVersion);
    if (entity.getStatus() == DeviceStatus.BAJA) {
      throw new DeviceDecommissionedException("No se puede editar un dispositivo dado de baja");
    }
    List<String> changedFields = new ArrayList<>();
    if (hostname != null) {
      entity.setHostname(hostname);
      changedFields.add("hostname");
    }
    if (mgmtIp != null) {
      entity.setMgmtIp(mgmtIp);
      changedFields.add("mgmtIp");
    }
    if (deviceType != null) {
      entity.setDeviceType(toDomainType(deviceType));
      changedFields.add("deviceType");
    }
    if (vendor != null) {
      entity.setVendor(vendor);
      changedFields.add("vendor");
    }
    if (model != null) {
      entity.setModel(model);
      changedFields.add("model");
    }
    if (assetTag != null) {
      entity.setAssetTag(assetTag);
      changedFields.add("assetTag");
    }
    if (location != null) {
      entity.setLocation(toDomainLocation(location));
      changedFields.add("location");
    }
    if (criticality != null) {
      entity.setCriticality(toDomainCriticality(criticality));
      changedFields.add("criticality");
    }
    VersionedDevice updated = save(entity);
    outboxWriter.record(RabbitConfig.ROUTING_ASSET_UPDATED, updated.body(), changedFields);
    return updated;
  }

  /** RN8: el If-Match debe coincidir con la versión actual del recurso. */
  private static void checkVersion(
      com.redsegura.assetinventory.domain.Device entity, long expectedVersion) {
    if (entity.getVersion() == null || entity.getVersion() != expectedVersion) {
      throw new PreconditionFailedException(
          "El If-Match no coincide con la versión actual del recurso");
    }
  }

  private VersionedDevice save(com.redsegura.assetinventory.domain.Device entity) {
    var saved = saveEntity(entity);
    return new VersionedDevice(mapper.toResponse(saved), saved.getVersion());
  }

  private com.redsegura.assetinventory.domain.Device saveEntity(
      com.redsegura.assetinventory.domain.Device entity) {
    try {
      return repository.saveAndFlush(entity);
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new PreconditionFailedException("Edición concurrente detectada");
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
