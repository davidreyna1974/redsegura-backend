package com.redsegura.assetinventory.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * Escribe un {@link ProblemDetail} (RFC 7807, ADR-08) directamente en la respuesta HTTP. Lo usan los
 * manejadores de seguridad (401/403), que actúan en la cadena de filtros —antes de Spring MVC— y por
 * tanto no pueden apoyarse en {@code @RestControllerAdvice}.
 */
final class ProblemResponseWriter {

  private ProblemResponseWriter() {}

  static void write(
      HttpServletResponse response,
      ObjectMapper objectMapper,
      HttpStatus status,
      String title,
      String detail,
      String code)
      throws IOException {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setProperty("code", code);
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.getWriter().write(objectMapper.writeValueAsString(problem));
  }
}
