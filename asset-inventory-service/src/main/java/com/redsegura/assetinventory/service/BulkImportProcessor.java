package com.redsegura.assetinventory.service;

import com.redsegura.assetinventory.exception.DuplicateDeviceException;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Worker de importación masiva (RF-04). Procesa cada dispositivo de forma aislada (cada alta y cada
 * registro de resultado en su propia transacción): el fallo de un dispositivo (p. ej. duplicado) no
 * afecta a los demás. La ejecución es asíncrona; el endpoint responde 202 sin esperar.
 */
@Component
public class BulkImportProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(BulkImportProcessor.class);
  private static final int MAX_DETAIL = 500;

  private final DeviceService deviceService;
  private final JobService jobService;

  public BulkImportProcessor(DeviceService deviceService, JobService jobService) {
    this.deviceService = deviceService;
    this.jobService = jobService;
  }

  /** Encola el procesamiento del lote en el pool dedicado; retorna de inmediato. */
  @Async("bulkImportExecutor")
  public void enqueue(UUID jobId, List<DeviceCreateRequest> devices) {
    processBatch(jobId, devices);
  }

  /** Procesa el lote (síncrono). Visible para tests deterministas. */
  public void processBatch(UUID jobId, List<DeviceCreateRequest> devices) {
    jobService.markInProgress(jobId);
    try {
      for (DeviceCreateRequest device : devices) {
        processOne(jobId, device);
      }
      jobService.markCompleted(jobId);
    } catch (RuntimeException e) {
      LOG.error("event=bulk_import_failed job={} reason={}", jobId, e.getClass().getSimpleName());
      jobService.markFailed(jobId);
    }
  }

  private void processOne(UUID jobId, DeviceCreateRequest device) {
    try {
      deviceService.create(device, null);
      jobService.recordResult(jobId, device.getSerialNumber(), JobService.OUTCOME_CREATED, null);
    } catch (DuplicateDeviceException e) {
      jobService.recordResult(
          jobId, device.getSerialNumber(), JobService.OUTCOME_FAILED, "Duplicado: ya registrado");
    } catch (RuntimeException e) {
      jobService.recordResult(
          jobId, device.getSerialNumber(), JobService.OUTCOME_FAILED, truncate(e.getMessage()));
    }
  }

  private static String truncate(String detail) {
    if (detail == null) {
      return "Error de importación";
    }
    return detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
  }
}
