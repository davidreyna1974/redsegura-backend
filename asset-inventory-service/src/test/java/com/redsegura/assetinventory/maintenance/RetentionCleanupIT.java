package com.redsegura.assetinventory.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.repository.IdempotencyRepository;
import com.redsegura.assetinventory.repository.ImportJobRepository;
import com.redsegura.assetinventory.repository.ImportJobResultRepository;
import com.redsegura.assetinventory.repository.OutboxRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Retención de datos operativos: la purga borra <b>solo lo antiguo</b> (fuera de su ventana) y
 * conserva lo reciente y —crítico— los eventos de outbox <b>pendientes</b> (nunca publicados). Los
 * resultados de un job antiguo caen por {@code ON DELETE CASCADE}.
 */
class RetentionCleanupIT extends AbstractIntegrationTest {

  @Autowired private RetentionCleanup retentionCleanup;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private IdempotencyRepository idempotencyRepository;
  @Autowired private ImportJobRepository importJobRepository;
  @Autowired private ImportJobResultRepository importJobResultRepository;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    importJobRepository.deleteAll();
    idempotencyRepository.deleteAll();
    outboxRepository.deleteAll();
  }

  @Test
  void purge_deletesOnlyAgedRows_keepsRecentAndPending() {
    // outbox: publicado viejo (30 d) → borra; publicado reciente (1 h) → conserva; pendiente →
    // conserva.
    insertOutbox("now() - interval '30 days'", "now() - interval '30 days'");
    insertOutbox("now() - interval '1 hour'", "now() - interval '1 hour'");
    insertOutbox("now() - interval '30 days'", "NULL"); // pendiente: jamás se borra
    // idempotencia (ventana 2 d): vieja (30 d) → borra; reciente (1 h) → conserva.
    insertIdempotency("k-old", "now() - interval '30 days'");
    insertIdempotency("k-new", "now() - interval '1 hour'");
    // import jobs (retención 30 d): viejo (60 d) con resultado → borra (cascada); reciente →
    // conserva.
    UUID oldJob = UUID.randomUUID();
    insertImportJob(oldJob, "now() - interval '60 days'");
    insertImportJobResult(oldJob);
    insertImportJob(UUID.randomUUID(), "now()");

    int deleted = retentionCleanup.purge();

    assertThat(deleted).as("borró 1 outbox + 1 idempotencia + 1 job antiguos").isEqualTo(3);
    assertThat(outboxRepository.count()).as("quedan el reciente y el pendiente").isEqualTo(2);
    assertThat(idempotencyRepository.count()).isEqualTo(1);
    assertThat(importJobRepository.count()).isEqualTo(1);
    assertThat(importJobResultRepository.count()).as("el resultado cayó por cascada").isZero();
  }

  private void insertOutbox(String createdAt, String publishedAt) {
    jdbc.update(
        "INSERT INTO outbox_events "
            + "(id,event_id,aggregate_type,aggregate_id,event_type,payload,created_at,published_at) "
            + "VALUES (gen_random_uuid(),gen_random_uuid(),'Device',gen_random_uuid(),"
            + "'asset.created','{}',"
            + createdAt
            + ","
            + publishedAt
            + ")");
  }

  private void insertIdempotency(String key, String createdAt) {
    jdbc.update(
        "INSERT INTO idempotency_keys (id,id_key,created_by,request_hash,device_id,created_at) "
            + "VALUES (gen_random_uuid(),?,'u','h',gen_random_uuid(),"
            + createdAt
            + ")",
        key);
  }

  private void insertImportJob(UUID id, String createdAt) {
    jdbc.update(
        "INSERT INTO import_jobs (id,status,total,succeeded,failed,created_by,created_at,updated_at) "
            + "VALUES (?,'COMPLETED',1,1,0,'u',"
            + createdAt
            + ","
            + createdAt
            + ")",
        id);
  }

  private void insertImportJobResult(UUID jobId) {
    jdbc.update(
        "INSERT INTO import_job_results (id,job_id,serial_number,outcome,created_at) "
            + "VALUES (gen_random_uuid(),?,'S1','CREATED',now() - interval '60 days')",
        jobId);
  }
}
