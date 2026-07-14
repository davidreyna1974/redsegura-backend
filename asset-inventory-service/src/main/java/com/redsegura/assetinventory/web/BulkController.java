package com.redsegura.assetinventory.web;

import com.redsegura.assetinventory.domain.ImportJob;
import com.redsegura.assetinventory.generated.api.BulkApi;
import com.redsegura.assetinventory.generated.model.BulkImportRequest;
import com.redsegura.assetinventory.generated.model.Job;
import com.redsegura.assetinventory.mapper.JobMapper;
import com.redsegura.assetinventory.service.BulkImportService;
import com.redsegura.assetinventory.service.JobService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Importación masiva asíncrona (RF-04). Implementa la interfaz generada {@link BulkApi} (ADR-05).
 * El alta encola el procesamiento y responde 202 con el job (QUEUED); el estado se consulta luego.
 */
@RestController
@RequestMapping("/api/v1")
public class BulkController implements BulkApi {

  private final BulkImportService bulkImportService;
  private final JobService jobService;
  private final JobMapper jobMapper;

  public BulkController(
      BulkImportService bulkImportService, JobService jobService, JobMapper jobMapper) {
    this.bulkImportService = bulkImportService;
    this.jobService = jobService;
    this.jobMapper = jobMapper;
  }

  @Override
  public ResponseEntity<Job> bulkImportDevices(
      BulkImportRequest bulkImportRequest, String idempotencyKey) {
    ImportJob job = bulkImportService.submit(bulkImportRequest, idempotencyKey);
    Job dto = jobMapper.toDto(job, jobService.getResults(job.getId()));
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/devices/bulk/jobs/" + job.getId()))
        .body(dto);
  }

  @Override
  public ResponseEntity<Job> getBulkJob(UUID jobId) {
    ImportJob job = jobService.getJob(jobId);
    return ResponseEntity.ok(jobMapper.toDto(job, jobService.getResults(jobId)));
  }
}
