package com.redsegura.assetinventory.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Valida que el JWT vaya dirigido a este backend (RNF-29/ADR-14). Acepta el token si la audiencia
 * esperada aparece en el claim {@code aud} o, en su defecto, coincide con {@code azp} (authorized
 * party, el clientId que emitió el token en Keycloak). Un token dirigido a otra audiencia se
 * rechaza → 401, como defensa en profundidad frente a la reutilización de tokens de otros
 * servicios/clientes.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2Error INVALID_AUDIENCE =
      new OAuth2Error(
          "invalid_token",
          "El token no está dirigido a la audiencia esperada",
          "https://tools.ietf.org/html/rfc6750#section-3.1");

  private final String expectedAudience;

  public AudienceValidator(String expectedAudience) {
    this.expectedAudience = expectedAudience;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    if (jwt.getAudience() != null && jwt.getAudience().contains(expectedAudience)) {
      return OAuth2TokenValidatorResult.success();
    }
    if (expectedAudience.equals(jwt.getClaimAsString("azp"))) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
  }
}
