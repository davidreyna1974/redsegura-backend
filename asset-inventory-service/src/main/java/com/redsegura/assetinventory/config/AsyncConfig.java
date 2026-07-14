package com.redsegura.assetinventory.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

/** Ejecución asíncrona para la importación masiva (RF-04). Pool acotado con cola limitada. */
@Configuration
@EnableAsync
public class AsyncConfig {

  @Bean("bulkImportExecutor")
  Executor bulkImportExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("bulk-import-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    // Propaga el SecurityContext al hilo del worker: los dispositivos importados llevan el actor
    // real en created_by (auditoría, ADR-07), no "system".
    return new DelegatingSecurityContextAsyncTaskExecutor(executor);
  }
}
