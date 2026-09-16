package com.petever.api.repository;

import com.petever.api.entity.Animal;
import com.petever.api.entity.AnimalImage;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AnimalImageRepository extends JpaRepository<AnimalImage, Long> {
    @Transactional
    void deleteByAnimalAndSource(Animal animal, String source);

    @Query("select image from AnimalImage image where image.id = :id and image.animal.visibility = :visibility")
    Optional<AnimalImage> findByIdAndAnimalVisibility(
            @Param("id") Long id,
            @Param("visibility") String visibility);
}
