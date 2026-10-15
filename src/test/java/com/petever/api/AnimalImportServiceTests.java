package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AnimalImportServiceTests {
    @Test
    void mapsV2BreedAndSpeciesNamesInsteadOfNumericCodes() {
        var animals = mock(AnimalRepository.class);
        var importer = new AnimalImportService(mock(ShelterRepository.class), animals,
                mock(AnimalExternalRecordRepository.class), mock(AnimalImageRepository.class));
        var item = new ObjectMapper().readTree("""
            {"desertionNo":"V2-1","careNm":"보호소","upKindCd":"417000","upKindNm":"개",
             "kindCd":"000020","kindNm":"보더 콜리","kindFullNm":"[개] 보더 콜리"}
            """);

        assertTrue(importer.importItem(item, null));

        var animalCaptor = org.mockito.ArgumentCaptor.forClass(Animal.class);
        verify(animals).save(animalCaptor.capture());
        assertEquals("DOG", animalCaptor.getValue().species);
        assertEquals("보더 콜리", animalCaptor.getValue().breedName);
    }

    @Test
    void usesFullBreedNameWhenV2ShortNameIsMissing() {
        var animals = mock(AnimalRepository.class);
        var importer = new AnimalImportService(mock(ShelterRepository.class), animals,
                mock(AnimalExternalRecordRepository.class), mock(AnimalImageRepository.class));
        var item = new ObjectMapper().readTree("""
            {"desertionNo":"V2-2","careNm":"보호소","upKindCd":"999999",
             "kindCd":"000200","kindFullNm":"[고양이] 한국 고양이"}
            """);

        assertTrue(importer.importItem(item, null));

        var animalCaptor = org.mockito.ArgumentCaptor.forClass(Animal.class);
        verify(animals).save(animalCaptor.capture());
        assertEquals("CAT", animalCaptor.getValue().species);
        assertEquals("한국 고양이", animalCaptor.getValue().breedName);
    }

    @Test
    void doesNotExposeNumericBreedOrGuessUnknownSpecies() {
        var animals = mock(AnimalRepository.class);
        var importer = new AnimalImportService(mock(ShelterRepository.class), animals,
                mock(AnimalExternalRecordRepository.class), mock(AnimalImageRepository.class));
        var item = new ObjectMapper().readTree("""
            {"desertionNo":"V2-3","careNm":"보호소","upKindCd":"999999","kindCd":"000020"}
            """);

        assertTrue(importer.importItem(item, null));

        var animalCaptor = org.mockito.ArgumentCaptor.forClass(Animal.class);
        verify(animals).save(animalCaptor.capture());
        assertEquals("UNKNOWN", animalCaptor.getValue().species);
        assertNull(animalCaptor.getValue().breedName);
    }

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

    @Test
    void storesAllSupportedPublicImagesInStableOrder() {
        var images = mock(AnimalImageRepository.class);
        var importer = new AnimalImportService(mock(ShelterRepository.class), mock(AnimalRepository.class),
                mock(AnimalExternalRecordRepository.class), images);
        var item = new ObjectMapper().readTree("""
            {"desertionNo":"A4","careNm":"보호소","kindCd":"[개] 믹스견",
             "popfile1":"https://example.org/1.jpg","popfile2":"https://example.org/1.jpg",
             "popfile3":"https://example.org/3.jpg","popfile4":"https://example.org/4.jpg",
             "evntImg":"https://example.org/event.jpg"}
            """);

        assertTrue(importer.importItem(item, "6110000"));

        var captor = org.mockito.ArgumentCaptor.forClass(AnimalImage.class);
        verify(images, times(4)).save(captor.capture());
        assertEquals(List.of(
                "https://example.org/1.jpg", "https://example.org/3.jpg",
                "https://example.org/4.jpg", "https://example.org/event.jpg"),
                captor.getAllValues().stream().map(image -> image.url).toList());
        assertEquals(List.of(0, 1, 2, 3),
                captor.getAllValues().stream().map(image -> image.sortOrder).toList());
    }
}
