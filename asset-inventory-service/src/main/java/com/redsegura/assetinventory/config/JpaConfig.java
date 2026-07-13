package com.redsegura.assetinventory.config;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Habilita las columnas de auditoría (ADR-07): {@code created/updated_at/by} auto-pobladas. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {

  /**
   * Proveedor del usuario actual para {@code created_by}/{@code updated_by}.
   *
   * <p><b>Provisional:</b> devuelve {@code "system"} hasta cablear la identidad del JWT (Keycloak)
   * en el hito de seguridad; entonces leerá el {@code sub}/usuario del {@code SecurityContext}.
   */
  @Bean
  AuditorAware<String> auditorAware() {
    return () -> Optional.of("system");
  }
}
