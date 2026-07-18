package com.redsegura.assetinventory.maintenance;

import com.redsegura.assetinventory.repository.IdempotencyRepository;
import com.redsegura.assetinventory.repository.ImportJobRepository;
import com.redsegura.assetinventory.repository.OutboxRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retención de datos operativos: purga filas que ya cumplieron su función y solo harían crecer las
 * tablas sin límite (disco y degradación de queries a largo plazo). Borra:
 *
 * <ul>
 *   <li><b>outbox_events</b> ya <b>publicados</b> anteriores a la retención (los pendientes nunca
 *       se tocan);
 *   <li><b>idempotency_keys</b> anteriores a la ventana de idempotencia;
 *   <li><b>import_jobs</b> anteriores a la retención (sus resultados caen por {@code ON DELETE
 *       CASCADE}).
 * </ul>
 *
 * <p>Las ventanas son configurables (días). La lógica vive aquí; el disparo periódico lo hace
 * {@link RetentionScheduler} (desactivable, como el relay del outbox).
 */
@Component
public class RetentionCleanup {

  private static final Logger LOG = LoggerFactory.getLogger(RetentionCleanup.class);

  private final OutboxRepository outboxRepository;
  private final IdempotencyRepository idempotencyRepository;
  private final ImportJobRepository importJobRepository;
  private final Duration outboxRetention;
  private final Duration idempotencyRetention;
  private final Duration importJobsRetention;

  public RetentionCleanup(
      OutboxRepository outboxRepository,
      IdempotencyRepository idempotencyRepository,
      ImportJobRepository importJobRepository,
      @Value("${redsegura.retention.outbox-published-days:7}") long outboxDays,
      @Value("${redsegura.retention.idempotency-days:2}") long idempotencyDays,
      @Value("${redsegura.retention.import-jobs-days:30}") long importJobsDays) {
    this.outboxRepository = outboxRepository;
    this.idempotencyRepository = idempotencyRepository;
    this.importJobRepository = importJobRepository;
    this.outboxRetention = Duration.ofDays(outboxDays);
    this.idempotencyRetention = Duration.ofDays(idempotencyDays);
    this.importJobsRetention = Duration.ofDays(importJobsDays);
  }

  /**
   * Ejecuta la purga de las tres tablas en una transacción. Devuelve el total de filas borradas.
   */
  @Transactional
  public int purge() {
    Instant now = Instant.now();
    int outbox = outboxRepository.deletePublishedBefore(now.minus(outboxRetention));
    int idempotency = idempotencyRepository.deleteCreatedBefore(now.minus(idempotencyRetention));
    int importJobs = importJobRepository.deleteCreatedBefore(now.minus(importJobsRetention));
    int total = outbox + idempotency + importJobs;
    if (total > 0) {
      LOG.info(
          "event=retention_purge outbox={} idempotency={} import_jobs={}",
          outbox,
          idempotency,
          importJobs);
    }
    return total;
  }
}
