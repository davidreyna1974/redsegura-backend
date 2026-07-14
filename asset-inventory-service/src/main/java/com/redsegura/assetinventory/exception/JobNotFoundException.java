package com.redsegura.assetinventory.exception;

import java.util.UUID;

/** Job de importación masiva inexistente → 404. */
public class JobNotFoundException extends RuntimeException {

  public JobNotFoundException(UUID jobId) {
    super("Job no encontrado: " + jobId);
  }
}
