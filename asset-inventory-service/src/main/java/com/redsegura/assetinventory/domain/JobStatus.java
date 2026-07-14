package com.redsegura.assetinventory.domain;

/** Estado de un job de importación masiva (contrato §Job). */
public enum JobStatus {
  QUEUED,
  IN_PROGRESS,
  COMPLETED,
  FAILED
}
