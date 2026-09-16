package com.petever.api.controller;

import com.petever.api.entity.*;
import com.petever.api.repository.AnimalRepository;
import com.petever.api.repository.AnimalExternalRecordRepository;
import com.petever.api.service.AnimalImportService;
import java.util.Set;
import java.util.stream.Collectors;


import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("supabase")
@RequestMapping("/api/animals")
public class AnimalController {
    public record Summary(Long id, String name, String species, String breedName, String careStatus, String imageUrl, String listingType) {}
    public record AnimalPage(List<Summary> content, int number, int totalPages, long totalElements) {}
    public record Detail(Long id, String name, String species, String breedName, String sex, String ageDescription,
                  String careStatus, String foundPlace, String description, String shelterName,
                  String shelterPhone, List<String> images, String listingType) {}
    private final AnimalRepository animals;
    private final AnimalExternalRecordRepository records;

    public AnimalController(AnimalRepository animals, AnimalExternalRecordRepository records) { this.animals = animals; this.records = records; }

    @Transactional(readOnly = true)
    @GetMapping
    public AnimalPage list(@RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "12") int size,
                           @RequestParam(required = false) String species,
                           @RequestParam(name = "care_status", required = false) String careStatus,
                           @RequestParam(name = "listing_type", required = false) String listingType) {
        if (page < 0 || size < 1 || size > 50) throw new IllegalArgumentException("Invalid pagination");
        species = filter(species, List.of("DOG", "CAT", "OTHER", "UNKNOWN"));
        careStatus = filter(careStatus, List.of("PROTECTED", "ADOPTED", "RETURNED", "TRANSFERRED", "DECEASED", "OTHER_CLOSED", "UNKNOWN"));
        listingType = filter(listingType, List.of("LOST_REPORT", "SHELTER_ANIMAL"));
        Page<Animal> result = animals.findPublic(species, careStatus, listingType,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        // listingType is inferred from animal_external_records.source; update this mapping
        // (here and in AnimalRepository.findPublic) whenever a new source is added.
        Set<Long> lostIds = records.findByAnimalIdIn(result.getContent().stream().map(a -> a.id).toList()).stream()
                .filter(r -> AnimalImportService.LOSS_SOURCE.equals(r.source))
                .map(r -> r.animal.id).collect(Collectors.toSet());
        List<Summary> content = result.getContent().stream().map(a ->
                new Summary(a.id, a.name, a.species, a.breedName, a.careStatus,
                        a.images.isEmpty() ? null : imagePath(a.images.getFirst()),
                        lostIds.contains(a.id) ? "LOST_REPORT" : "SHELTER_ANIMAL")).toList();
        return new AnimalPage(content, result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    @GetMapping("/{id}")
    public Detail detail(@PathVariable Long id) {
        Animal a = animals.findByIdAndVisibility(id, "PUBLIC")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean lost = records.findByAnimalId(id)
                .map(r -> AnimalImportService.LOSS_SOURCE.equals(r.source)).orElse(false);
        return new Detail(a.id, a.name, a.species, a.breedName, a.sex, a.ageDescription,
                a.careStatus, a.foundPlace, a.description,
                a.shelter == null ? null : a.shelter.name, a.shelter == null ? null : a.shelter.phone,
                a.images.stream().map(AnimalController::imagePath).toList(),
                lost ? "LOST_REPORT" : "SHELTER_ANIMAL");
    }

    private static String imagePath(AnimalImage image) {
        return "/api/animals/images/" + image.id;
    }

    private static String filter(String value, List<String> allowed) {
        if (value == null || value.isBlank()) return null;
        if (!allowed.contains(value)) throw new IllegalArgumentException("Invalid filter value");
        return value;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    String invalid(IllegalArgumentException ex) { return ex.getMessage(); }
}
