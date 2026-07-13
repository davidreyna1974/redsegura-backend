package com.redsegura.assetinventory.web;

import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.DeviceStatus;
import com.redsegura.assetinventory.domain.DeviceType;
import com.redsegura.assetinventory.service.DeviceService;
import com.redsegura.assetinventory.web.dto.DeviceCreateRequest;
import com.redsegura.assetinventory.web.dto.DeviceResponse;
import com.redsegura.assetinventory.web.dto.DeviceUpdateRequest;
import com.redsegura.assetinventory.web.dto.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST del inventario de dispositivos (RF-01..05).
 *
 * <p>Alcance del hito actual: CRUD y reglas de negocio (RN1..RN7). La seguridad (RBAC), la
 * concurrencia {@code ETag}/{@code If-Match}, la idempotencia y la redacción de {@code mgmtIp} se
 * añaden en el hito transversal.
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

  private final DeviceService service;

  public DeviceController(DeviceService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<DeviceResponse> create(@Valid @RequestBody DeviceCreateRequest request) {
    DeviceResponse created = service.create(request);
    return ResponseEntity.created(URI.create("/api/v1/devices/" + created.id())).body(created);
  }

  @GetMapping
  public PageResponse<DeviceResponse> list(
      @RequestParam(required = false) String hostname,
      @RequestParam(required = false) String mgmtIp,
      @RequestParam(required = false) String serialNumber,
      @RequestParam(required = false) DeviceType deviceType,
      @RequestParam(required = false) String site,
      @RequestParam(required = false) String rack,
      @RequestParam(required = false) Criticality criticidad,
      @RequestParam(required = false) DeviceStatus estado,
      @PageableDefault(size = 20) Pageable pageable) {
    return service.search(
        hostname, mgmtIp, serialNumber, deviceType, site, rack, criticidad, estado, pageable);
  }

  @GetMapping("/{deviceId}")
  public DeviceResponse get(@PathVariable UUID deviceId) {
    return service.findById(deviceId);
  }

  @PutMapping("/{deviceId}")
  public DeviceResponse replace(
      @PathVariable UUID deviceId, @Valid @RequestBody DeviceUpdateRequest request) {
    return service.update(deviceId, request);
  }

  @PatchMapping("/{deviceId}")
  public DeviceResponse update(
      @PathVariable UUID deviceId, @Valid @RequestBody DeviceUpdateRequest request) {
    return service.update(deviceId, request);
  }

  @DeleteMapping("/{deviceId}")
  public ResponseEntity<Void> decommission(@PathVariable UUID deviceId) {
    service.decommission(deviceId);
    return ResponseEntity.noContent().build();
  }
}
