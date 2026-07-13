package com.redsegura.assetinventory.web;

import com.redsegura.assetinventory.generated.api.DevicesApi;
import com.redsegura.assetinventory.generated.model.Criticality;
import com.redsegura.assetinventory.generated.model.Device;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import com.redsegura.assetinventory.generated.model.DeviceStatus;
import com.redsegura.assetinventory.generated.model.DeviceType;
import com.redsegura.assetinventory.generated.model.DeviceUpdateFull;
import com.redsegura.assetinventory.generated.model.DeviceUpdateRequest;
import com.redsegura.assetinventory.generated.model.PageDevice;
import com.redsegura.assetinventory.service.DeviceService;
import java.net.URI;
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
 * prefijo. Las cabeceras {@code If-Match}/{@code Idempotency-Key} se reciben pero su lógica
 * (concurrencia/idempotencia) se implementa en el hito transversal.
 */
@RestController
@RequestMapping("/api/v1")
public class DeviceController implements DevicesApi {

  private final DeviceService service;

  public DeviceController(DeviceService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<Device> createDevice(
      DeviceCreateRequest deviceCreateRequest, String idempotencyKey) {
    Device created = service.create(deviceCreateRequest);
    return ResponseEntity.created(URI.create("/api/v1/devices/" + created.getId())).body(created);
  }

  @Override
  public ResponseEntity<Device> getDevice(UUID deviceId) {
    return ResponseEntity.ok(service.findById(deviceId));
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
            toDomainStatus(estado),
            toPageable(page, size, sort));
    return ResponseEntity.ok(toPageDevice(result));
  }

  @Override
  public ResponseEntity<Device> replaceDevice(
      UUID deviceId, String ifMatch, DeviceUpdateFull deviceUpdateFull) {
    return ResponseEntity.ok(service.replace(deviceId, deviceUpdateFull));
  }

  @Override
  public ResponseEntity<Device> updateDevice(
      UUID deviceId, String ifMatch, DeviceUpdateRequest deviceUpdateRequest) {
    return ResponseEntity.ok(service.update(deviceId, deviceUpdateRequest));
  }

  @Override
  public ResponseEntity<Void> decommissionDevice(UUID deviceId, String ifMatch) {
    service.decommission(deviceId);
    return ResponseEntity.noContent().build();
  }

  private static Pageable toPageable(Integer page, Integer size, String sort) {
    int p = page == null ? 0 : page;
    int s = size == null ? 20 : size;
    if (sort == null || sort.isBlank()) {
      return PageRequest.of(p, s);
    }
    String[] parts = sort.split(",");
    Sort.Direction dir =
        parts.length > 1 && parts[1].equalsIgnoreCase("desc")
            ? Sort.Direction.DESC
            : Sort.Direction.ASC;
    return PageRequest.of(p, s, Sort.by(dir, parts[0]));
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
