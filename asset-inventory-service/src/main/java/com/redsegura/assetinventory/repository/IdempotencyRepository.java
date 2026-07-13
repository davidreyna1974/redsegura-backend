package com.redsegura.assetinventory.repository;

import com.redsegura.assetinventory.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistencia de las claves de idempotencia (RN9). */
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {}
