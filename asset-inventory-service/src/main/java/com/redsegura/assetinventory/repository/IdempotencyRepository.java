package com.redsegura.assetinventory.repository;

import com.redsegura.assetinventory.domain.IdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistencia de las claves de idempotencia (RN9), acotadas por (clave, usuario). */
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {

  Optional<IdempotencyRecord> findByIdKeyAndCreatedBy(String idKey, String createdBy);
}
