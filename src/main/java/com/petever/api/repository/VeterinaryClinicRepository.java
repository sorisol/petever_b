package com.petever.api.repository;

import com.petever.api.entity.VeterinaryClinic;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface VeterinaryClinicRepository extends JpaRepository<VeterinaryClinic, Long>,
        JpaSpecificationExecutor<VeterinaryClinic> {
    @Query("select distinct v.regionCode from VeterinaryClinic v where v.regionCode is not null order by v.regionCode")
    List<String> findRegionCodes();

    default Page<VeterinaryClinic> findByFilters(String sido, String sigungu, String status, Pageable pageable) {
        Specification<VeterinaryClinic> filters = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (sigungu == null) {
                String escaped = sido.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicates.add(builder.like(root.get("regionCode"), escaped + "|%", '\\'));
            } else {
                predicates.add(builder.equal(root.get("regionCode"), sido + "|" + sigungu));
            }
            if (status != null) predicates.add(builder.equal(root.get("operatingStatus"), status));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return findAll(filters, pageable);
    }
}
