package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("supabase")
@SpringBootTest(properties = {
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:animal-schema.sql",
    "spring.jpa.hibernate.ddl-auto=validate",
    "LOSSINFO_API_KEY=",
    "ANIMAL_API_SERVICE_KEY="
})
class AnimalSchemaTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ShelterRepository shelters;
    @Autowired AnimalRepository animals;
    @Autowired AnimalExternalRecordRepository records;
    @Autowired SyncRunRepository runs;

    @Test
    void persistsAnimalWithNullShelter() {
        var animal = new Animal();
        animal.shelter = null;
        animal = animals.saveAndFlush(animal);
        assertNull(animals.findById(animal.id).orElseThrow().shelter);
    }

    @Test
    void validatesSchemaAndPersistsJsonb() {
        var shelter = new Shelter();
        shelter.name = "Test shelter";
        shelter = shelters.saveAndFlush(shelter);
        var animal = new Animal();
        animal.shelter = shelter;
        animal = animals.saveAndFlush(animal);
        var record = new AnimalExternalRecord();
        record.animal = animal;
        record.source = AnimalImportService.SOURCE;
        record.externalId = "test-1";
        record.rawPayload = "{\"desertionNo\":\"test-1\"}";
        record.lastSeenAt = Instant.now();
        record.lastSyncedAt = record.lastSeenAt;
        records.saveAndFlush(record);
        assertEquals("test-1", records.findBySourceAndExternalId(AnimalImportService.SOURCE, "test-1").orElseThrow().externalId);
    }

    @Test
    void publicImageLookupDoesNotExposeAnImageOwnedByAHiddenAnimal() {
        var publicAnimal = animals.saveAndFlush(new Animal());
        var publicImage = new AnimalImage();
        publicImage.animal = publicAnimal;
        publicImage.source = "PUBLIC_API";
        publicImage.url = "https://example.org/public.jpg";
        publicImage = images.saveAndFlush(publicImage);

        var hiddenAnimal = new Animal();
        hiddenAnimal.visibility = "HIDDEN";
        hiddenAnimal = animals.saveAndFlush(hiddenAnimal);
        var hiddenImage = new AnimalImage();
        hiddenImage.animal = hiddenAnimal;
        hiddenImage.source = "PUBLIC_API";
        hiddenImage.url = "https://example.org/hidden.jpg";
        hiddenImage = images.saveAndFlush(hiddenImage);

        org.junit.jupiter.api.Assertions.assertTrue(
                images.findByIdAndAnimalVisibility(publicImage.id, "PUBLIC").isPresent());
        org.junit.jupiter.api.Assertions.assertTrue(
                images.findByIdAndAnimalVisibility(hiddenImage.id, "PUBLIC").isEmpty());
    }

    @Autowired AnimalImportService importer;
    @Autowired AnimalImageRepository images;
    @Autowired AnimalController controller;

    @Test
    void importRoundTripPreservesLocalFieldsAndSupportsPublicQuery() {
        var item = new tools.jackson.databind.ObjectMapper().readTree("""
            {"desertionNo":"roundtrip-1","careRegNo":"shelter-1","careNm":"Test shelter",
             "kindCd":"[개] Collie","processState":"보호중","weight":"3.5(Kg)",
             "popfile1":"https://example.org/animal.jpg"}
            """);
        org.junit.jupiter.api.Assertions.assertTrue(importer.importItem(item, "3220000"));
        var animal = animals.findAll().stream().filter(a -> "Collie".equals(a.breedName)).findFirst().orElseThrow();
        assertEquals(0, new java.math.BigDecimal("3.5").compareTo(animal.weightKg));
        animal.name = "Local name";
        animal.description = "Local description";
        animal.statusAuthority = "SHELTER";
        animal.careStatus = "PROTECTED";
        animals.saveAndFlush(animal);
        var localPhoto = new AnimalImage();
        localPhoto.animal = animal;
        localPhoto.source = "SHELTER";
        localPhoto.url = "https://example.org/local.jpg";
        localPhoto.sortOrder = 0;
        images.saveAndFlush(localPhoto);
        org.junit.jupiter.api.Assertions.assertFalse(importer.importItem(item, "3220000"));
        var detail = controller.detail(animal.id);
        assertEquals("Local name", detail.name());
        assertEquals("Local description", detail.description());
        assertEquals(2, detail.images().size());
        var page = controller.list(0, 50, null, null, null);
        org.junit.jupiter.api.Assertions.assertTrue(page.content().stream().anyMatch(a -> a.id().equals(animal.id)));
    }
    @Test
    void lostReportRoundTripIsIdempotentAndFilteredAsLost() {
        var item = new tools.jackson.databind.ObjectMapper().readTree("""
            {"callName":"private-person","callTel":"private-phone","happenAddr":"private-address",
             "happenPlace":"private-place","specialMark":"private-note",
             "happenDt":"2026-09-14 13:00:00.0","orgNm":"가상시 중구",
             "popfile":"http://openapi.animal.go.kr/openapi/service/rest/fileDownloadSrvc/files/loss/2026/09/integration.jpg",
             "kindCd":"믹스견","colorCd":"흰색","sexCd":"F"}
            """);
        org.junit.jupiter.api.Assertions.assertTrue(importer.importItem(item, null));
        org.junit.jupiter.api.Assertions.assertFalse(importer.importItem(item, null));
        var record = records.findBySourceAndExternalId(AnimalImportService.LOSS_SOURCE,
                AnimalImportService.lossId(item)).orElseThrow();

        org.junit.jupiter.api.Assertions.assertFalse(record.rawPayload.contains("private-"));
        var page = controller.list(0, 50, null, null, "LOST_REPORT");
        var summary = page.content().stream().filter(a -> "LOST_REPORT".equals(a.listingType()))
                .findFirst().orElseThrow();
        assertEquals(1, records.findAll().stream().filter(r -> AnimalImportService.LOSS_SOURCE.equals(r.source)
                && AnimalImportService.lossId(item).equals(r.externalId)).count());
        var detail = controller.detail(summary.id());
        assertEquals("LOST_REPORT", detail.listingType());
        assertEquals("가상시 중구", detail.foundPlace());
        org.junit.jupiter.api.Assertions.assertNull(detail.shelterName());
    }
    @Test
    void testContextDoesNotStartLiveAnimalSync() {
        assertEquals(0, runs.count());
    }
}
