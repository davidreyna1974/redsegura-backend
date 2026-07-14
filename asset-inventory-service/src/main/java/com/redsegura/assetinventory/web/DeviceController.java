package com.redsegura.assetinventory.web;

import com.redsegura.assetinventory.generated.api.DevicesApi;
import com.redsegura.assetinventory.generated.model.Criticality;
import com.redsegura.assetinventory.generated.model.Device;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import com.redsegura.assetinventory.generated.model.DeviceStatus;
import com.redsegura.assetinventory.generated.model.DeviceType;
import com.redsegura.assetinventory.generated.model.DeviceUpdateFull;
import com.redsegura.assetinventory.generated.model.DeviceUpdateRequest;
import com.redsegura.assetinventory.exception.InvalidRequestException;
import com.redsegura.assetinventory.generated.model.PageDevice;
import com.redsegura.assetinventory.security.MgmtIpRedactor;
import com.redsegura.assetinventory.security.SecurityAuditLogger;
import com.redsegura.assetinventory.service.DeviceService;
import com.redsegura.assetinventory.service.VersionedDevice;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa la interfaz generada del contrato ({@link DevicesApi}, ADR-05): el código cumple el
 * contrato por construcción (si el contrato cambia, esto deja de compilar hasta actualizarse).
 *
 * <p>El generador mapea a {@code /devices}; el {@code @RequestMapping("/api/v1")} de clase añade el
 * prefijo. Las cabeceras {@code If-Match} (concurrencia optimista, ADR-09) e {@code Idempotency-Key}
 * (deduplicación de altas, RN9) están implementadas.
 */
@RestController
@RequestMapping("/api/v1")
public class DeviceController implements DevicesApi {

  private final DeviceService service;
  private final MgmtIpRedactor redactor;
  private final SecurityAuditLogger auditLogger;

  public DeviceController(
      DeviceService service, MgmtIpRedactor redactor, SecurityAuditLogger auditLogger) {
    this.service = service;
    this.redactor = redactor;
    this.auditLogger = auditLogger;
  }

  @Override
  public ResponseEntity<Device> createDevice(
      DeviceCreateRequest deviceCreateRequest, String idempotencyKey) {
    VersionedDevice vd = service.create(deviceCreateRequest, idempotencyKey);
    auditLogger.mutation("CREATE_DEVICE", vd.body().getId());
    return ResponseEntity.created(URI.create("/api/v1/devices/" + vd.body().getId()))
        .eTag(etag(vd.version()))
        .body(vd.body());
  }

  @Override
  public ResponseEntity<Device> getDevice(UUID deviceId) {
    VersionedDevice vd = service.findById(deviceId);
    redactor.maybeRedact(vd.body());
    return ResponseEntity.ok().eTag(etag(vd.version())).body(vd.body());
  }

  @Override
  public ResponseEntity<PageDevice> listDevices(
      String hostname,
      String mgmtIp,
      String serialNumber,
      DeviceType deviceType,
      String site,
      String rack,
      Criticality criticidad,
      String fabricante,
      String modelo,
      DeviceStatus estado,
      Integer page,
      Integer size,
      String sort) {
    Page<Device> result =
        service.search(
            hostname,
            mgmtIp,
            serialNumber,
            toDomainType(deviceType),
            site,
            rack,
            toDomainCriticality(criticidad),
            fabricante,
            modelo,
            toDomainStatus(estado),
            toPageable(page, size, sort));
    redactor.maybeRedact(result.getContent());
    return ResponseEntity.ok(toPageDevice(result));
  }

  @Override
  public ResponseEntity<Device> replaceDevice(
      UUID deviceId, String ifMatch, DeviceUpdateFull deviceUpdateFull) {
    VersionedDevice vd = service.replace(deviceId, parseIfMatch(ifMatch), deviceUpdateFull);
    auditLogger.mutation("REPLACE_DEVICE", vd.body().getId());
    return ResponseEntity.ok().eTag(etag(vd.version())).body(vd.body());
  }

  @Override
  public ResponseEntity<Device> updateDevice(
      UUID deviceId, String ifMatch, DeviceUpdateRequest deviceUpdateRequest) {
    VersionedDevice vd = service.update(deviceId, parseIfMatch(ifMatch), deviceUpdateRequest);
    auditLogger.mutation("UPDATE_DEVICE", vd.body().getId());
    return ResponseEntity.ok().eTag(etag(vd.version())).body(vd.body());
  }

  @Override
  public ResponseEntity<Void> decommissionDevice(UUID deviceId, String ifMatch) {
    service.decommission(deviceId, parseIfMatch(ifMatch));
    auditLogger.mutation("DECOMMISSION_DEVICE", deviceId);
    return ResponseEntity.noContent().build();
  }

  private static String etag(long version) {
    return "\"" + version + "\"";
  }

  /**
   * Extrae la versión numérica del valor If-Match (ETag). Formato inválido -> -1 (no coincidirá).
   */
  private static long parseIfMatch(String ifMatch) {
    if (ifMatch == null) {
      return -1L;
    }
    String v = ifMatch.trim().replaceFirst("^W/", "").replace("\"", "").trim();
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  /** Tamaño de página máximo: acota el coste de una consulta (evita `size` sin límite → DoS). */
  private static final int MAX_PAGE_SIZE = 100;

  /** Campos permitidos para ordenar; un campo fuera de esta lista se rechaza con 400 (no 500). */
  private static final Set<String> SORTABLE_FIELDS =
      Set.of(
          "hostname",
          "mgmtIp",
          "serialNumber",
          "deviceType",
          "criticality",
          "status",
          "vendor",
          "model",
          "createdAt",
          "updatedAt");

  private static Pageable toPageable(Integer page, Integer size, String sort) {
    int p = Math.max(0, page == null ? 0 : page);
    int requested = size == null ? 20 : size;
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, requested));
    if (sort == null || sort.isBlank()) {
      return PageRequest.of(p, s);
    }
    String[] parts = sort.split(",");
    String field = parts[0].trim();
    if (!SORTABLE_FIELDS.contains(field)) {
      throw new InvalidRequestException(
          "Campo de ordenación no permitido: '" + field + "'. Permitidos: " + SORTABLE_FIELDS);
    }
    Sort.Direction dir =
        parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
            ? Sort.Direction.DESC
            : Sort.Direction.ASC;
    return PageRequest.of(p, s, Sort.by(dir, field));
  }

  private static PageDevice toPageDevice(Page<Device> page) {
    return new PageDevice()
        .content(page.getContent())
        .page(page.getNumber())
        .size(page.getSize())
        .totalElements((int) page.getTotalElements())
        .totalPages(page.getTotalPages())
        .first(page.isFirst())
        .last(page.isLast());
  }

  private static com.redsegura.assetinventory.domain.DeviceType toDomainType(DeviceType t) {
    return t == null ? null : com.redsegura.assetinventory.domain.DeviceType.valueOf(t.name());
  }

  private static com.redsegura.assetinventory.domain.Criticality toDomainCriticality(
      Criticality c) {
    return c == null ? null : com.redsegura.assetinventory.domain.Criticality.valueOf(c.name());
  }

  private static com.redsegura.assetinventory.domain.DeviceStatus toDomainStatus(DeviceStatus s) {
    return s == null ? null : com.redsegura.assetinventory.domain.DeviceStatus.valueOf(s.name());
  }
}
