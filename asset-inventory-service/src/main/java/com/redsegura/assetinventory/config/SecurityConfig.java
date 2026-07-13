package com.redsegura.assetinventory.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad de extremo a extremo (RNF-03/04): el servicio es un OAuth2 Resource Server que valida
 * el JWT de Keycloak y aplica RBAC por endpoint (los roles del contrato: ADM/OPE/AUD). La
 * autorización se valida aquí, en el servicio, no se asume que el API Gateway ya filtró.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final String ADM = "ADM";
  private static final String OPE = "OPE";
  private static final String AUD = "AUD";

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/v1/health/**", "/actuator/**")
                    .permitAll()
                    // Lectura del inventario: los tres roles.
                    .requestMatchers(HttpMethod.GET, "/api/v1/devices/**")
                    .hasAnyAuthority(ADM, OPE, AUD)
                    // Escritura (POST/PUT/PATCH/DELETE): solo Administrador.
                    .requestMatchers("/api/v1/devices/**")
                    .hasAuthority(ADM)
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  /** Extrae los roles de realm de Keycloak ({@code realm_access.roles}) como authorities. */
  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::extractRealmRoles);
    return converter;
  }

  @SuppressWarnings("unchecked")
  private static Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
    Map<String, Object> realmAccess = jwt.getClaim("realm_access");
    if (realmAccess == null) {
      return List.of();
    }
    List<String> roles = (List<String>) realmAccess.getOrDefault("roles", List.of());
    return roles.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r)).toList();
  }
}
