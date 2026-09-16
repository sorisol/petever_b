package com.petever.api.repository;

import com.petever.api.entity.*;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

public interface AnimalExternalRecordRepository extends JpaRepository<AnimalExternalRecord, Long> {
    Optional<AnimalExternalRecord> findBySourceAndExternalId(String source, String externalId);
    Optional<AnimalExternalRecord> findByAnimalId(Long animalId);
    List<AnimalExternalRecord> findByAnimalIdIn(Collection<Long> animalIds);
}
