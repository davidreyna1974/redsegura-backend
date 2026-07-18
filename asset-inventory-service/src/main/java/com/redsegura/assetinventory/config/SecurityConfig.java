package com.redsegura.assetinventory.config;

import com.redsegura.assetinventory.security.AudienceValidator;
import com.redsegura.assetinventory.web.ProblemAccessDeniedHandler;
import com.redsegura.assetinventory.web.ProblemAuthenticationEntryPoint;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
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
  SecurityFilterChain filterChain(
      HttpSecurity http,
      ProblemAuthenticationEntryPoint authenticationEntryPoint,
      ProblemAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/health/**",
                        "/actuator/**",
                        // OpenAPI navegable en runtime (RNF-27): doc pública, sin token.
                        "/openapi.yaml",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**")
                    .permitAll()
                    // Lectura del inventario: los tres roles.
                    .requestMatchers(HttpMethod.GET, "/api/v1/devices/**")
                    .hasAnyAuthority(ADM, OPE, AUD)
                    // Escritura (POST/PUT/PATCH/DELETE): solo Administrador.
                    .requestMatchers("/api/v1/devices/**")
                    .hasAuthority(ADM)
                    .anyRequest()
                    .authenticated())
        // 401/403 en application/problem+json (ADR-08) + traza de seguridad (ADR-11).
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  /**
   * Decoder de JWT con validación en profundidad (RNF-29/ADR-14): firma (JWKS), expiración,
   * <b>issuer</b> y <b>audience</b>. Se construye desde el {@code jwk-set-uri} (perezoso: no
   * descarga los JWKs al arrancar, por eso no requiere Keycloak vivo en los tests), y se le añaden
   * los validadores de emisor y audiencia esperados. Un token de otro realm/audiencia → 401.
   */
  @Bean
  JwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
      @Value("${redsegura.security.issuer}") String issuer,
      @Value("${redsegura.security.audience}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    decoder.setJwtValidator(jwtValidator(issuer, audience));
    return decoder;
  }

  /**
   * Validador compuesto del token (RNF-29/ADR-14): expiración + <b>issuer</b> ({@link
   * JwtValidators}) + <b>audience</b> ({@link AudienceValidator}). Extraído para que la validación
   * exacta de producción sea verificable en un test sin depender de un Keycloak vivo.
   */
  static OAuth2TokenValidator<Jwt> jwtValidator(String issuer, String audience) {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer), new AudienceValidator(audience));
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
