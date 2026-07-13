package com.redsegura.assetinventory.web;

import com.redsegura.assetinventory.exception.DeviceDecommissionedException;
import com.redsegura.assetinventory.exception.DeviceNotFoundException;
import com.redsegura.assetinventory.exception.DuplicateDeviceException;
import com.redsegura.assetinventory.exception.PreconditionFailedException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Manejo centralizado de errores en formato RFC 7807 (ADR-08). Spring serializa {@link
 * ProblemDetail} como {@code application/problem+json}. No se filtran detalles internos (RNF-09).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(DeviceNotFoundException.class)
  ProblemDetail handleNotFound(DeviceNotFoundException ex) {
    return problem(
        HttpStatus.NOT_FOUND, "Recurso no encontrado", ex.getMessage(), "DEVICE_NOT_FOUND");
  }

  @ExceptionHandler(DuplicateDeviceException.class)
  ProblemDetail handleDuplicate(DuplicateDeviceException ex) {
    return problem(
        HttpStatus.CONFLICT, "Conflicto de unicidad", ex.getMessage(), "DEVICE_ALREADY_EXISTS");
  }

  @ExceptionHandler(DeviceDecommissionedException.class)
  ProblemDetail handleDecommissioned(DeviceDecommissionedException ex) {
    return problem(
        HttpStatus.CONFLICT, "Dispositivo dado de baja", ex.getMessage(), "DEVICE_DECOMMISSIONED");
  }

  /** RN8: el If-Match no coincide con la versión actual -> 412. */
  @ExceptionHandler(PreconditionFailedException.class)
  ProblemDetail handlePreconditionFailed(PreconditionFailedException ex) {
    return problem(
        HttpStatus.PRECONDITION_FAILED,
        "Precondición fallida",
        ex.getMessage(),
        "PRECONDITION_FAILED");
  }

  /** RN8: falta la cabecera If-Match obligatoria -> 428. */
  @ExceptionHandler(MissingRequestHeaderException.class)
  ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
    if ("If-Match".equalsIgnoreCase(ex.getHeaderName())) {
      return problem(
          HttpStatus.PRECONDITION_REQUIRED,
          "Falta If-Match",
          "La cabecera If-Match es obligatoria",
          "PRECONDITION_REQUIRED");
    }
    return problem(HttpStatus.BAD_REQUEST, "Cabecera faltante", ex.getMessage(), "MISSING_HEADER");
  }

  /** Validación de Bean Validation → 422 (entidad bien formada pero semánticamente inválida). */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY, "Una o más validaciones fallaron");
    pd.setTitle("Validación fallida");
    pd.setProperty("code", "VALIDATION_ERROR");
    List<String> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .toList();
    pd.setProperty("errors", errors);
    return ResponseEntity.unprocessableEntity().body(pd);
  }

  private ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setTitle(title);
    pd.setProperty("code", code);
    return pd;
  }
}
