package com.redsegura.assetinventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.domain.ImportJob;
import com.redsegura.assetinventory.domain.JobStatus;
import com.redsegura.assetinventory.generated.model.Criticality;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import com.redsegura.assetinventory.generated.model.DeviceType;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.ImportJobRepository;
import com.redsegura.assetinventory.repository.ImportJobResultRepository;
import com.redsegura.assetinventory.repository.OutboxRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Importación masiva (RF-04) a nivel worker (síncrono, determinista): cada dispositivo se procesa
 * aislado; un duplicado falla solo ese ítem y el resto continúa.
 */
class BulkImportIT extends AbstractIntegrationTest {

  @Autowired private BulkImportProcessor processor;
  @Autowired private JobService jobService;
  @Autowired private DeviceRepository deviceRepository;
  @Autowired private ImportJobRepository jobRepository;
  @Autowired private ImportJobResultRepository resultRepository;
  @Autowired private OutboxRepository outboxRepository;

  @BeforeEach
  void clean() {
    resultRepository.deleteAll();
    jobRepository.deleteAll();
    outboxRepository.deleteAll();
    deviceRepository.deleteAll();
  }

  private static DeviceCreateRequest req(String serial, String hostname, String ip) {
    return new DeviceCreateRequest(serial, hostname, ip, DeviceType.SWITCH, Criticality.ALTA);
  }

  @Test
  void processBatch_createsEachDevice_andIsolatesDuplicates() {
    ImportJob job = jobService.createQueued(3, null, "tester", null);
    List<DeviceCreateRequest> batch =
        List.of(
            req("S1", "SW1", "10.0.0.1"),
            req("S2", "SW2", "10.0.0.2"),
            req("S1", "SW3", "10.0.0.3")); // serial duplicado -> falla solo este

    processor.processBatch(job.getId(), batch);

    ImportJob done = jobService.getJob(job.getId());
    assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(done.getTotal()).isEqualTo(3);
    assertThat(done.getSucceeded()).isEqualTo(2);
    assertThat(done.getFailed()).isEqualTo(1);
    assertThat(deviceRepository.count()).isEqualTo(2);
    assertThat(jobService.getResults(job.getId()))
        .extracting("outcome")
        .containsExactlyInAnyOrder("CREATED", "CREATED", "FAILED");
  }

  @Test
  void getJob_missing_throwsNotFound() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobService.getJob(UUID.randomUUID()))
        .isInstanceOf(com.redsegura.assetinventory.exception.JobNotFoundException.class);
  }
}
