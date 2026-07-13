package com.redsegura.assetinventory.config;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Habilita las columnas de auditoría (ADR-07): {@code created/updated_at/by} auto-pobladas. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {

  /**
   * Proveedor del usuario actual para {@code created_by}/{@code updated_by}: el sujeto del JWT
   * (Keycloak) del {@code SecurityContext}. Si no hay usuario autenticado (procesos internos, tests
   * directos de servicio), usa {@code "system"}.
   */
  @Bean
  AuditorAware<String> auditorAware() {
    return () -> {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
        return Optional.of("system");
      }
      return Optional.of(auth.getName());
    };
  }
}
