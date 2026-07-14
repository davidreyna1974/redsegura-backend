package com.redsegura.assetinventory.mapper;

import com.redsegura.assetinventory.domain.ImportJob;
import com.redsegura.assetinventory.domain.ImportJobResult;
import com.redsegura.assetinventory.generated.model.Job;
import com.redsegura.assetinventory.generated.model.JobResultsInner;
import java.util.List;
import org.springframework.stereotype.Component;

/** Mapea el job de importación de dominio (+ sus resultados) al DTO {@code Job} del contrato. */
@Component
public class JobMapper {

  public Job toDto(ImportJob job, List<ImportJobResult> results) {
    return new Job()
        .jobId(job.getId())
        .status(Job.StatusEnum.fromValue(job.getStatus().name()))
        .total(job.getTotal())
        .succeeded(job.getSucceeded())
        .failed(job.getFailed())
        .results(results.stream().map(JobMapper::toResultDto).toList());
  }

  private static JobResultsInner toResultDto(ImportJobResult result) {
    return new JobResultsInner()
        .serialNumber(result.getSerialNumber())
        .outcome(result.getOutcome())
        .detail(result.getDetail());
  }
}
