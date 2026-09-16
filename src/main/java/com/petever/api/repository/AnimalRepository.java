package com.petever.api.repository;

import com.petever.api.entity.*;

import java.util.ArrayList;
import java.util.Optional;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

public interface AnimalRepository extends JpaRepository<Animal, Long>, JpaSpecificationExecutor<Animal> {
    default Page<Animal> findPublic(String species, String careStatus, Pageable pageable) {
        return findPublic(species, careStatus, null, pageable);
    }
    default Page<Animal> findPublic(String species, String careStatus, String listingType, Pageable pageable) {
        Specification<Animal> publicAnimals = (root, query, builder) -> {
            var conditions = new ArrayList<Predicate>();
            conditions.add(builder.equal(root.get("visibility"), "PUBLIC"));
            if (species != null) conditions.add(builder.equal(root.get("species"), species));
            if (careStatus != null) conditions.add(builder.equal(root.get("careStatus"), careStatus));
            if (listingType != null) {
                Subquery<Long> lossIds = query.subquery(Long.class);
                Root<AnimalExternalRecord> external = lossIds.from(AnimalExternalRecord.class);
                lossIds.select(external.get("animal").get("id"))
                        .where(builder.equal(external.get("source"), "LOSS_INFO"));
                if ("LOST_REPORT".equals(listingType)) conditions.add(root.get("id").in(lossIds));
                else conditions.add(builder.not(root.get("id").in(lossIds)));
            }
            return builder.and(conditions.toArray(Predicate[]::new));
        };
        return findAll(publicAnimals, pageable);
    }
    Optional<Animal> findByIdAndVisibility(Long id, String visibility);
}
