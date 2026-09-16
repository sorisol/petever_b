package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AnimalImportServiceTests {
    @Test
    void upsertPreservesLocalFieldsAndImages() {
        var shelters = mock(ShelterRepository.class);
        var animals = mock(AnimalRepository.class);
        var records = mock(AnimalExternalRecordRepository.class);
        var images = mock(AnimalImageRepository.class);
        var importer = new AnimalImportService(shelters, animals, records, images);
        var item = new ObjectMapper().readTree("""
            {"desertionNo":"A1","careRegNo":"S1","careNm":"보호소","kindCd":"[개] 믹스견",
             "processState":"종료(입양)","popfile1":"https://example.org/photo.jpg"}
            """);
        var shelter = new Shelter();
        var animal = new Animal();
        animal.shelter = shelter;
        animal.name = "보리";
        animal.description = "담당자가 작성한 소개";
        animal.statusAuthority = "SHELTER";
        animal.careStatus = "PROTECTED";
        animal.visibility = "HIDDEN";
        var record = new AnimalExternalRecord();
        record.animal = animal;
        when(records.findBySourceAndExternalId("ANIMAL_API", "A1")).thenReturn(Optional.of(record));
        when(shelters.findByExternalSourceAndExternalId("ANIMAL_API", "S1")).thenReturn(Optional.of(shelter));

        assertFalse(importer.importItem(item, "6110000"));
        assertEquals("보리", animal.name);
        assertEquals("담당자가 작성한 소개", animal.description);
        assertEquals("PROTECTED", animal.careStatus);
        assertEquals("HIDDEN", animal.visibility);
        assertEquals("종료(입양)", record.externalStatus);
        assertTrue(record.rawPayload.contains("A1"));
        verify(images).deleteByAnimalAndSource(animal, "PUBLIC_API");
        verify(images).save(any(AnimalImage.class));
    }

    @Test
    void missingShelterIdDoesNotFabricateExternalIdentity() {
        var shelters = mock(ShelterRepository.class);
        var importer = new AnimalImportService(shelters, mock(AnimalRepository.class),
                mock(AnimalExternalRecordRepository.class), mock(AnimalImageRepository.class));
        var item = new ObjectMapper().readTree("""
            {"desertionNo":"A2","careNm":"같은 이름 보호소","kindCd":"[고양이] 코리안숏헤어"}
            """);
        assertTrue(importer.importItem(item, "6110000"));
        verify(shelters).save(org.mockito.ArgumentMatchers.argThat(s ->
                s.externalSource == null && s.externalId == null));
    }

    @Test
    void ignoresImplausibleWeightValueInsteadOfOverflowingTheColumn() {
        var animals = mock(AnimalRepository.class);
        var importer = new AnimalImportService(mock(ShelterRepository.class), animals,
                mock(AnimalExternalRecordRepository.class), mock(AnimalImageRepository.class));
        var item = new ObjectMapper().readTree("""
            {"desertionNo":"A3","careNm":"보호소","kindCd":"[개] 믹스견","weight":"20260914"}
            """);

        assertTrue(importer.importItem(item, "6110000"));

        var animalCaptor = org.mockito.ArgumentCaptor.forClass(Animal.class);
        verify(animals).save(animalCaptor.capture());
        org.junit.jupiter.api.Assertions.assertNull(animalCaptor.getValue().weightKg);
    }
}
