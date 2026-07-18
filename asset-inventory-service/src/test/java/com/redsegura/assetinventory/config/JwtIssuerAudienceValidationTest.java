package com.redsegura.assetinventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * RNF-29/ADR-14 — camino negativo: el validador de token EXACTO que arma la producción ({@link
 * SecurityConfig#jwtValidator}) debe rechazar tokens con <b>issuer</b> o <b>audience</b>
 * equivocados, y aceptar solo el correcto. Verifica que la <b>composición</b> issuer+audience está
 * cableada, no solo una pieza (complementa {@code AudienceValidatorTest}).
 */
class JwtIssuerAudienceValidationTest {

  private static final String ISSUER = "http://localhost:8080/realms/redsegura";
  private static final String AUDIENCE = "redsegura-backend";

  private final OAuth2TokenValidator<Jwt> validator = SecurityConfig.jwtValidator(ISSUER, AUDIENCE);

  private static Jwt.Builder validBase() {
    return Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .subject("user")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300));
  }

  @Test
  void accepts_tokenWithCorrectIssuerAndAudience() {
    Jwt jwt = validBase().claim("iss", ISSUER).audience(List.of(AUDIENCE)).build();

    assertThat(validator.validate(jwt).hasErrors()).isFalse();
  }

  @Test
  void rejects_tokenFromAnotherIssuer() {
    Jwt jwt =
        validBase()
            .claim("iss", "http://attacker.example/realms/otro")
            .audience(List.of(AUDIENCE))
            .build();

    assertThat(validator.validate(jwt).hasErrors()).isTrue();
  }

  @Test
  void rejects_tokenForAnotherAudience() {
    Jwt jwt =
        validBase()
            .claim("iss", ISSUER)
            .audience(List.of("otro-servicio"))
            .claim("azp", "x")
            .build();

    assertThat(validator.validate(jwt).hasErrors()).isTrue();
  }
}
