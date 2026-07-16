package com.redsegura.assetinventory.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** RNF-29/ADR-14: el token solo se acepta si va dirigido a la audiencia esperada (aud o azp). */
class AudienceValidatorTest {

  private static final String EXPECTED = "redsegura-backend";
  private final AudienceValidator validator = new AudienceValidator(EXPECTED);

  private static Jwt.Builder baseJwt() {
    return Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .subject("user")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60));
  }

  @Test
  void accepts_whenAudClaimContainsExpected() {
    Jwt jwt = baseJwt().claim("aud", List.of(EXPECTED, "account")).build();

    assertThat(validator.validate(jwt).hasErrors()).isFalse();
  }

  @Test
  void accepts_whenAzpMatchesExpected() {
    Jwt jwt = baseJwt().claim("aud", List.of("account")).claim("azp", EXPECTED).build();

    assertThat(validator.validate(jwt).hasErrors()).isFalse();
  }

  @Test
  void rejects_whenAudienceIsAnotherService() {
    Jwt jwt = baseJwt().claim("aud", List.of("otro-servicio")).claim("azp", "otro-cliente").build();

    var result = validator.validate(jwt);

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors().iterator().next().getErrorCode()).isEqualTo("invalid_token");
  }
}
